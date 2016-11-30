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

	public DRSRunnable(ServiceInstance si) throws RemoteException{
		this.si = si;
		rootFolder = si.getRootFolder();
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("ClusterComputeResource");
		if(mes.length > 0){
			cluster = (ClusterComputeResource)mes[0];
		}
		migrationThreshold = 5;
		targetBalance = 0.010;
	}
	
	public void run(){
		if(cluster == null){
			System.err.println("There are no drs clusters");
			return;
		}
		if(running == true){
			System.err.println("DRS is already running");
			return;
		}
		running = true;
		while(running == true){
			try{
				currentBalance = calculateCurrentBalance();
				System.out.println("Current Balance: " + currentBalance + "\tTarget Balance: " + targetBalance);
				if(currentBalance > targetBalance){
					getBestMove();
				}
				Thread.sleep(5000);
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
					hostCPUUsageArrayPercent[k] = hostCPUUsageArray[k]/ (double)(numOfCores * cpuMhz);
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
				System.out.println(hypotheticalBalance);
				hostCPUUsageArray[j] -= vmCPUUsage;
			}
		}
		System.out.println("min SD = " + minSD);
		System.out.println("Source vm = " + mes[sourceVMIndex].getName());
		System.out.println("Source Host = " + hss[sourceHostIndex].getName());
		System.out.println("Target Host = " + hss[targetHostIndex].getName());
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
			hostCPUUsage[j] /= 100;
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
}
