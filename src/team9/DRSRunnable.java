package team9;
import java.rmi.RemoteException;
import java.text.DecimalFormat;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import com.vmware.vim25.mo.util.MorUtil;

public class DRSRunnable implements Runnable{
	private ServiceInstance si;
	private Folder rootFolder;
	private ClusterComputeResource cluster;
	private volatile boolean running = false;
	Integer migrationThreshold;
	double targetBalance;
	double currentBalance;
	double[][] tBM;

	public DRSRunnable(ServiceInstance si) throws RemoteException{
		this.si = si;
		rootFolder = si.getRootFolder();
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("ClusterComputeResource");
		if(mes.length > 0){
			cluster = (ClusterComputeResource)mes[0];
		}
		createTargetBalanceMatrix();
		setMigrationThreshold(6);
	}
	
	private void createTargetBalanceMatrix(){
		tBM = new double[4][];
		for(int i = 0; i < 5; i++){
			tBM[i] = new double[5];
		}
		//number of hosts == 2
		tBM[0][0] = .284; tBM[0][1] = .183; tBM[0][2] = .093; tBM[0][3] = .046; tBM[0][4] = .010;
		//number of hosts == 3
		tBM[1][0] = .244; tBM[1][1] = .163; tBM[1][2] = .081; tBM[1][3] = .040; tBM[1][4] = .010;
		//number of hosts == 4
		tBM[2][0] = .212; tBM[2][1] = .141; tBM[2][2] = .070; tBM[2][3] = .035;	tBM[2][4] = .010;
		//number of hosts == 5
		tBM[3][0] = .189; tBM[3][1] = .126; tBM[3][2] = .063; tBM[3][3] = .031;	tBM[3][4] = .010;
		//number of hosts > 5
		tBM[4][0] = .150; tBM[4][1] = .100; tBM[4][2] = .055; tBM[4][3] = .028;	tBM[4][4] = .010;
	}
	
	public void setMigrationThreshold(Integer migrationThreshold){
		if(migrationThreshold == null)
			return;
		this.migrationThreshold = migrationThreshold;
		setTargetBalance();
	}
	
	private void setTargetBalance(){
		if(cluster == null)
			return;
		int numHost = cluster.getSummary().getNumHosts();
		if(numHost == 0){
			targetBalance = 100;
		}
		if(numHost > 1 && numHost < 6){
			targetBalance = tBM[numHost -2][migrationThreshold - 2];
		}else{
			targetBalance = tBM[4][migrationThreshold - 2];
		}
	}
	
	public void run(){
		if(cluster == null){
			System.err.println("\tThere are no drs clusters");
			return;
		}
		if(running == true){
			System.err.println("\tDRS is already running");
			return;
		}
		running = true;
		while(running == true){
			try{
				currentBalance = calculateCurrentBalance();
				System.out.println("\tCurrent Balance: " + currentBalance + "\tTarget Balance: " + targetBalance);
				if(currentBalance > targetBalance){
					getBestMove();
				}
				Thread.sleep(10000);
			}catch(RemoteException e){
				System.err.println(e.getMessage());
			}catch(InterruptedException e){
				System.err.println(e.getMessage());
			}
		}
		System.out.println("Terminating drs");
	}
	
	public void terminate() {
        running = false;
    }
	
	public void getBestMove() throws RemoteException{
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
		ManagedEntity[] hss = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");
		double minSD = currentBalance;
		int sourceVMIndex = -1;
		int sourceHostIndex = -1;
		int targetHostIndex = -1;
		//for each vm
		for(int i =0 ;i < mes.length; i++){
			VirtualMachine vm = (VirtualMachine)mes[i];
			if(vm.getRuntime().getPowerState().toString().equals("poweredOff")){
				continue;
			}
			HostSystem sourceHost = getHostOfVM(vm);
			//System.out.println(sourceHost.getName());
			//System.out.println("Source Host cpu % " + getHostCpuUsagePecentage(sourceHost));
			int vmCPUUsage = vm.getSummary().getQuickStats().getOverallCpuUsage();
			//System.out.println("VM cpu usage" + vmCPUUsage);
			int sourceHostCPUUsage = sourceHost.getSummary().getQuickStats().getOverallCpuUsage();
			double[] hostCPUUsageArray = new double[hss.length];
			
			int numOfCores = sourceHost.getSummary().getHardware().getNumCpuCores();
			int cpuMhz = sourceHost.getSummary().getHardware().getCpuMhz();
					
			//for each hosts that is not source host
			int currentHostIndex = -1;
			for(int j = 0 ; j < hss.length; j++){
				HostSystem hs = (HostSystem)hss[j];
				if(hs.getName().equals(sourceHost.getName())){
					//hostCPUUsageArray[j] = ((sourceHostCPUUsage - vmCPUUsage) / (double)(numOfCores * cpuMhz));
					currentHostIndex = j;
					hostCPUUsageArray[j] = sourceHostCPUUsage - vmCPUUsage;
					continue;
				}
				hostCPUUsageArray[j] = hs.getSummary().getQuickStats().getOverallCpuUsage();
			}
			
			for(int j = 0; j < hostCPUUsageArray.length; j++){
				if(j == currentHostIndex)
					continue;
				//System.out.println("Host[" + j + "]" + hostCPUUsageArray[j] + " ");
				hostCPUUsageArray[j] += vmCPUUsage;
				
				double[] hostCPUUsageArrayPercent = new double[hss.length];
				for(int k = 0; k < hss.length; k++){
					HostSystem temp = (HostSystem)hss[k];
					numOfCores = temp.getSummary().getHardware().getNumCpuCores();
					cpuMhz = temp.getSummary().getHardware().getCpuMhz();
					hostCPUUsageArrayPercent[k] = hostCPUUsageArray[k]/ (double)(numOfCores * cpuMhz)/10;
					//System.out.println("\t " + hostCPUUsageArray[k]);
					//System.out.println("\t% " + hostCPUUsageArrayPercent[k]);
				}
				double hypotheticalBalance = calculateSampleSD(hostCPUUsageArrayPercent);
				if(hypotheticalBalance < minSD){
					minSD = hypotheticalBalance;
					sourceVMIndex = i;
					sourceHostIndex = currentHostIndex;
					targetHostIndex = j;
				}
				System.out.println("\tPossible balance by migrating vm: " + hypotheticalBalance);
				hostCPUUsageArray[j] -= vmCPUUsage;
			}
		}
		if(minSD < (currentBalance * .85) && minSD > (currentBalance * .25)){
			//if the new standard deviation is 15% smaller than the current standard deviation
			VirtualMachine sourceVM = (VirtualMachine)mes[sourceVMIndex];
			HostSystem sourceHost = (HostSystem)hss[sourceHostIndex];
			HostSystem targetHost = (HostSystem)hss[targetHostIndex];
			System.out.println("\tmin SD = " + minSD);
			System.out.println("\tSource vm = " + sourceVM.getName());
			System.out.println("\tSource Host = " + sourceHost.getName());
			System.out.println("\tTarget Host = " + targetHost.getName());
			ComputeResource cr = (ComputeResource)targetHost.getParent();
			ResourcePool rp = cr.getResourcePool();
			
			try{
				System.out.println("\tMigrating");
				migrateVM(rp, sourceVM, targetHost);
				Thread.sleep(10000);
			}catch(Exception e){
				System.err.println(e.getMessage());
			}
			
		}
		
	}
	
	public HostSystem getHostOfVM(VirtualMachine vm) {
		return (HostSystem) MorUtil.createExactManagedEntity(si.getServerConnection(), vm
		                    .getRuntime().getHost());
	}
	
	private double calculateCurrentBalance() throws RemoteException{
		double hostCurrentBalance = 0;
		HostSystem hss[] = cluster.getHosts();
		double[] hostCPUUsage = new double[hss.length];
		for(int j = 0; j < hss.length; j++){
			hostCPUUsage[j] = getHostCpuUsagePecentage(hss[j]);
			hostCPUUsage[j] /= 1000;
		}
		hostCurrentBalance = calculateSampleSD(hostCPUUsage);
		return hostCurrentBalance;
	}
	
	private double calculateSampleSD(double[] a){
		if(a.length < 2){
			return 0;
		}
		double average = 0;
		for(int i = 0; i < a.length; i++){
			average += a[i];
		}
		average /= a.length;
		double t = 0;
		for(int i = 0; i < a.length; i++){
			t += ((a[i] - average) * (a[i] - average));
		}
		return Math.sqrt(t/(a.length - 1));
		
	}
	
	public double getHostCpuUsagePecentage(HostSystem hs) {
		int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
		int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
		int usedCpuMhz = hs.getSummary().getQuickStats().getOverallCpuUsage();
		double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
		
		DecimalFormat df = new DecimalFormat("####0.00");
		return Double.valueOf(df.format(percent));
	}
	
	public boolean migrateVM(ResourcePool rp, VirtualMachine vm, HostSystem host) throws VmConfigFault, Timedout, FileFault, InvalidState, InsufficientResourcesFault, MigrationFault, RuntimeFault, RemoteException, InterruptedException
	{
		Task task = vm.migrateVM_Task(rp, host,VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOn);
	    if(task.waitForTask()==Task.SUCCESS)
	    {
	      return true;
	    }
	    else
	    {
	      TaskInfo info = task.getTaskInfo();
	      System.out.println(info.getError().getFault());
	      return false;
	    }
	}
	public boolean checkCompatible(VirtualMachine vm, HostSystem host) throws RuntimeFault, RemoteException
	{
		String[] checks = new String[] {"cpu", "software"};
		HostVMotionCompatibility[] vmcs = si.queryVMotionCompatibility(vm, new HostSystem[]{host},checks );
		String[] comps = vmcs[0].getCompatibility();
	    if(checks.length != comps.length)
	    {
	      return true;
	    }
	    return false;
	}
	
}
