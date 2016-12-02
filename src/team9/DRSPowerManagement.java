package team9;

import java.rmi.RemoteException;
import java.text.DecimalFormat;

import com.vmware.vim25.FileFault;
import com.vmware.vim25.InsufficientResourcesFault;
import com.vmware.vim25.InvalidState;
import com.vmware.vim25.MigrationFault;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInfo;
import com.vmware.vim25.Timedout;
import com.vmware.vim25.VirtualMachineMovePriority;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VmConfigFault;
import com.vmware.vim25.mo.ClusterComputeResource;
import com.vmware.vim25.mo.ComputeResource;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.util.MorUtil;


public class DRSPowerManagement implements Runnable{
	
	private volatile boolean running = false;
	private ServiceInstance si;
	private Folder rootFolder;
	private ClusterComputeResource cluster;
	double targetBalance;
	double currentBalance;
	HostInfo hssInfo[];
	final int scalePoint = 90;
	final int timeOnOff = 30;


	public DRSPowerManagement(ServiceInstance si) throws RemoteException{
		this.si = si;
		rootFolder = si.getRootFolder();
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("ClusterComputeResource");
		if(mes.length > 0){
			cluster = (ClusterComputeResource)mes[0];
		}
	}
	
	class HostInfo {
		public HostSystem hs;
		public int powerStatus; //0=OFF, 1=ON, 2=Standby
		public int cpuCapacityInMhz;
		public double cpuUsagePercentage;
		public VmInfo vmsInfo[];
		
		public HostInfo(HostSystem hs) {
			this.hs = hs;
			setHostInfo();
		}
		
		public void refreshHostInfo() {
			for (HostInfo hsInfo : hssInfo) {
				if (hsInfo.powerStatus == 1) {
					cpuUsagePercentage = getHostCpuUsagePecentage(hs);
					try {
						VirtualMachine vms[] = hs.getVms();
						vmsInfo = new VmInfo[vms.length];
						for (int i = 0; i < vms.length; i++) {
							vmsInfo[i] = new VmInfo(vms[i]);
						}
					} catch (Exception e) { System.err.println(e.getMessage()); }
				}
			}
		}
		
		public void setHostInfo() {
			cpuUsagePercentage = getHostCpuUsagePecentage(hs);
			try {
				VirtualMachine vms[] = hs.getVms();
				if (vms.length < 1) {
					powerStatus = 2;

					vmsInfo = new VmInfo[vms.length];
					for (int i = 0; i < vms.length; i++) {
						vmsInfo[i] = new VmInfo(vms[i]);
					}
				} else { powerStatus = 1; }
			} catch (Exception e) { System.err.println(e.getMessage()); }		
			cpuCapacityInMhz = getHostCpuCapacity(hs);
		}
		
		public VirtualMachine getBiggestVmByCpu() {
			if (powerStatus == 1) { //ON 
				VirtualMachine vm = vmsInfo[0].vm;
				int biggest = vmsInfo[0].cpuInMhz;
				
				for (int i=1; i<vmsInfo.length; i++) {
					if (biggest < vmsInfo[i].cpuInMhz) {
						vm = vmsInfo[i].vm;
						biggest = vmsInfo[i].cpuInMhz;
					}
				}
				return vm;
			}
			return null;
		}
		
		public int getSumVmCpu() {
			int totalCPU = 0;
			for (VmInfo vmInfo: vmsInfo) {
				if (vmInfo.vm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOn)
					totalCPU += vmInfo.cpuInMhz;
			}
			return totalCPU;
		}
	}
	
	class VmInfo {
		public VirtualMachine vm;
		public int cpuInMhz;
		
		public VmInfo(VirtualMachine vm) {
			this.vm = vm;
			HostSystem hs = getHostOfVM(vm);
			int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
			int noCores = vm.getSummary().getConfig().getNumCpu();
			this.cpuInMhz = cpuMhz * noCores;
		}
		
	}
	
	public void printHostsAndVMs() throws RemoteException{
		
		System.out.println("\n--------------------------------------------------------------");
		for(int i = 0; i < hssInfo.length; i++){
			System.out.println("Host[" + i + "]: " + hssInfo[i].hs.getName() + 
						", Power state: "+ (hssInfo[i].powerStatus==1?"ON":"Standby") +
						" - <<CPU usage: " + getHostCpuUsagePecentage(hssInfo[i].hs) + "%>>");
			VirtualMachine[] vms = hssInfo[i].hs.getVms();
			for(int j = 0 ; j < vms.length; j++){
				int numOfCores = hssInfo[i].hs.getSummary().getHardware().getNumCpuCores();
				int cpuMhz = hssInfo[i].hs.getSummary().getHardware().getCpuMhz();
				int usedCpuMhz = vms[j].getSummary().getQuickStats().getOverallCpuUsage();
				double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
				
				DecimalFormat df = new DecimalFormat("####0.00");
				System.out.println("   + VM[" + j + "]: <" + vms[j].getName() + "> - Power state: " + vms[j].getRuntime().getPowerState()
						+ ", CPU usage: " + Double.valueOf(df.format(percent)) + "%");
			}
			System.out.println();
		}
		System.out.println("--------------------------------------------------------------\n");
	}
	
	
	public void initialHostStatus() {
		HostSystem hss[] = cluster.getHosts();
		hssInfo = new HostInfo[hss.length];
		for (int i = 0; i < hss.length; i++) {
			hssInfo[i] = new HostInfo(hss[i]);
		}
	}
	
	public int getHostCpuCapacity(HostSystem hs) {
		int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
		int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
		return numOfCores * cpuMhz;
	}
	
	public double getHostCpuUsagePecentage(HostSystem hs) {
		int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
		int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
		int usedCpuMhz = hs.getSummary().getQuickStats().getOverallCpuUsage();
		double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
		
		DecimalFormat df = new DecimalFormat("####0.00");
		return Double.valueOf(df.format(percent));
	}
	
	public int getVMCpuUsageInMhz(VirtualMachine vm) {
		return vm.getSummary().getQuickStats().getOverallCpuUsage();
	}
	
	public void refreshAllHosts() {
		for (int i = 0; i < hssInfo.length; i++) {
			hssInfo[i].refreshHostInfo();
		}
	}
	
	public void run(){
		if (cluster == null){
			System.err.println("There are no drs clusters");
			return;
		}
		
		if (running == true){
			System.err.println("DRS is already running");
			return;
		}
		
		initialHostStatus();
		running = true;
		while (running){
			try{
				refreshAllHosts();
				if (!scaleOut())
					scaleIn();
				Thread.sleep(30000);
				printHostsAndVMs();
			} catch(Exception e){
				System.err.println(e.getMessage());
			}
		}
		System.out.println("Terminating DRS Power Management");
	}
	
	public boolean scaleOut() {
		for (int i=0; i<hssInfo.length; i++) {
			System.out.println("SCALE OUT ("+hssInfo[i].hs.getName()+") percentage: " + hssInfo[i].cpuUsagePercentage);
			if (hssInfo[i].powerStatus == 1 && hssInfo[i].cpuUsagePercentage > scalePoint) {
				for (int j=0; j<hssInfo.length; j++) {
					if (hssInfo[j].powerStatus == 2) { //Standby
						try {
							turnOnHost(hssInfo[j]);
								
							ComputeResource cr = (ComputeResource)hssInfo[j].hs.getParent();
							ResourcePool rp = cr.getResourcePool();
							System.out.println("SCALE OUT: Moving " + hssInfo[i].getBiggestVmByCpu().getName() + " to host " + hssInfo[j].hs.getName() );
							migrateVM(rp, hssInfo[i].getBiggestVmByCpu(), hssInfo[j].hs);
							return true;
						} catch (Exception e) { 
							System.err.println(e.getMessage());
						}
					}
				}
			}
		}
		return false;
	}
	
	public boolean scaleIn() {

		for (int i=0; i<hssInfo.length; i++) {
			if (hssInfo[i].powerStatus == 1) {
				for (int j=i+1; j<hssInfo.length; j++) {
					if (hssInfo[j].powerStatus == 1) { 
						double percent = ((hssInfo[i].getSumVmCpu()+hssInfo[j].getSumVmCpu()) / (double)hssInfo[i].cpuCapacityInMhz) * 100;
						System.out.println("SCALE IN ("+hssInfo[j].hs.getName()+") percentage: " + percent);
						if (percent < scalePoint) {
							//move all VMs from hosts j to i
							try {
								for (int m=0; m<hssInfo[j].vmsInfo.length; m++) {
									ComputeResource cr = (ComputeResource)hssInfo[i].hs.getParent();
									ResourcePool rp = cr.getResourcePool();
									System.out.println("SCALE IN: Moving " + hssInfo[j].vmsInfo[m].vm.getName() + " to host " + hssInfo[i].hs.getName() );
									migrateVM(rp, hssInfo[j].vmsInfo[m].vm, hssInfo[i].hs);
								}
								//then standby host j
								standByHost(hssInfo[j]);
								return true;
							} catch (Exception e) { 
								System.err.println(e.getMessage());
							}
						}
					}
				}
			}
		}
		return false;
	}
	
	public boolean migrateVM(ResourcePool rp, VirtualMachine vm, HostSystem host) throws VmConfigFault, Timedout, FileFault, InvalidState, InsufficientResourcesFault, MigrationFault, RuntimeFault, RemoteException, InterruptedException {
		Task task = vm.migrateVM_Task(rp, host,VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOn);
	    
		if (task.waitForTask()==Task.SUCCESS) {
	      return true;
	    } else {
	      TaskInfo info = task.getTaskInfo();
	      System.out.println(info.getError().getFault());
	      return false;
	    }
	}
/* UNSUPPORTED BY HARDWARE, will be replaced with simulated methods	for host's power states
	public boolean turnOnHost(HostInfo hsInfo) throws HostPowerOpFailed, InvalidState, NotSupported, Timedout, RuntimeFault, RemoteException, InterruptedException {
		
		ManagedObjectReference mor = si.getServerConnection().getVimService().powerUpHostFromStandBy_Task(hsInfo.hs.getMOR(), timeOnOff);
		Task task = new Task(si.getServerConnection(), mor);
		if(task.waitForTask() == Task.SUCCESS) {
			System.out.println("Powering up host " + hsInfo.hs.getName());
			return true;
		}
		return false;
	}
	
	public boolean standByHost(HostInfo hsInfo) throws HostPowerOpFailed, InvalidState, NotSupported, Timedout, RuntimeFault, RemoteException, InterruptedException {
		ManagedObjectReference mor = si.getServerConnection().getVimService().powerDownHostToStandBy_Task(hsInfo.hs.getMOR(), timeOnOff, new Boolean(false));
		Task task = new Task(si.getServerConnection(), mor);
		if(task.waitForTask() == Task.SUCCESS) {
			System.out.println("Standing by host " + hsInfo.hs.getName());
			return true;
		}
		return false;
	}
*/
	
	public void turnOnHost(HostInfo hsInfo) {
		System.out.println("Powering up host " + hsInfo.hs.getName());
		hsInfo.setHostInfo();
	}
	
	public void standByHost(HostInfo hsInfo) {
		System.out.println("Standing by up host " + hsInfo.hs.getName());
		hsInfo.setHostInfo();
	}
	
	
	
	public void terminate() {
        running = false;
    }
	
	public HostSystem getHostOfVM(VirtualMachine vm) {
		return (HostSystem) MorUtil.createExactManagedEntity(si.getServerConnection(), vm
		                    .getRuntime().getHost());
	}
}
