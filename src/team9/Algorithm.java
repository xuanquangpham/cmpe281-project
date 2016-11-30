package team9;

import java.net.MalformedURLException;
import java.net.URL;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import java.rmi.RemoteException;
import java.text.DecimalFormat;
import java.util.Scanner;


public class Algorithm{
	ServiceInstance si;
	Integer cpuThreshold;
	Integer migrationThreshold;
	
	private void migrate(Scanner scanner) throws RemoteException, InterruptedException{
		Integer currentHost;
		Integer currentVM;
		Integer newHost;
		System.out.println("Migrate vm: <currentHost> <currentVM> <newHost>:");
		currentHost = scanner.nextInt();
		currentVM = scanner.nextInt();
		newHost = scanner.nextInt();
		//get rootFolder
		Folder rootFolder = si.getRootFolder();
		//get Array of all hosts
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");
		ComputeResource cr = (ComputeResource)mes[newHost].getParent();
		//get target resource pool (new host's resource pool)
		ResourcePool rp = cr.getResourcePool();
		migrateVM(rp, ((HostSystem)mes[currentHost]).getVms()[currentVM], ((HostSystem)mes[newHost]) );
		
	}
	
	public void printHostsAndVMs() throws RemoteException{
		Folder rootFolder = si.getRootFolder();
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");
		
		for(int i = 0; i < mes.length; i++){
			HostSystem hs = (HostSystem)mes[i];
			System.out.println("Host: " + hs.getName() + ", <<CPU usage = " + getHostCpuUsagePecentage(hs) + "% - " +
										"Memory usage = " + getHostMemoryUsagePecentage(hs) + "%>>");
			VirtualMachine[] vms = hs.getVms();
			for(int j =0 ;j < vms.length; j++){
				int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
				int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
				int usedCpuMhz = vms[j].getSummary().getQuickStats().getOverallCpuUsage();
				double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
				DecimalFormat df = new DecimalFormat("####0.00");
				System.out.println("\tVM " + j + ": <" + vms[j].getName() + "> - Power state: " + vms[j].getRuntime().getPowerState()
						+ " CPU Usage: " + Double.valueOf(df.format(percent)));
			}
			System.out.println();
		}
	}
	
	public double getHostCpuUsagePecentage(HostSystem hs) {
		int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
		int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
		int usedCpuMhz = hs.getSummary().getQuickStats().getOverallCpuUsage();
		double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
		
		DecimalFormat df = new DecimalFormat("####0.00");
		return Double.valueOf(df.format(percent));
	}
	
	public double getHostMemoryUsagePecentage(HostSystem hs) {
		double mem = hs.getSummary().getHardware().getMemorySize() / (1024 * 1024);
        int usedMem = hs.getSummary().getQuickStats().getOverallMemoryUsage();
        double percent = (usedMem / mem) * 100;
		
		
		DecimalFormat df = new DecimalFormat("####0.00");
		return Double.valueOf(df.format(percent));
	}
	
	private void setCPUThreshold(Scanner scanner){
		System.out.println("Enter cpu threshold: ");
		cpuThreshold = scanner.nextInt();
	}
	
	private void setMigrationThreshold(Scanner scanner){
		System.out.print("Enter migration threshold: [1-5] (1 being more conservative, 5 being more aggressive: ");
		migrationThreshold = scanner.nextInt();
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
	
	private void printDRSClusters() throws RemoteException{
		Folder rootFolder = si.getRootFolder();
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("ClusterComputeResource");
		for(int i = 0; i < mes.length; i++){
			ClusterComputeResource cluster = (ClusterComputeResource)mes[i];
			System.out.println("Cluster[" + i + "]: " + cluster.getName());
			ClusterComputeResourceSummary ccrs = (ClusterComputeResourceSummary)cluster.getSummary();
			System.out.println("\tCluster's current balance: " + ccrs.getCurrentBalance());
			System.out.println("\tCluster's target balance: " + ccrs.getTargetBalance());
			System.out.println("\tCluster's number of vMotions: " + ccrs.getNumVmotions());
			HostSystem hss[] = cluster.getHosts();
			double[] hostCPUUsage = new double[hss.length];
			for(int j = 0; j < hss.length; j++){
				hostCPUUsage[j] = getHostCpuUsagePecentage(hss[j]);
				hostCPUUsage[j] /= 100;
				System.out.println("Host cpu usage: " + hostCPUUsage[j]);
			}
			System.out.println("Calculated sd: " + calculateSampleSD(hostCPUUsage));
		}
	}
	
	private void run() throws RemoteException, InterruptedException {
		Integer choice;
		Scanner scanner = new Scanner(System.in);
		do{
			System.out.println("0. Print all hosts and their respective virtual machines\n"
					+ "1. Migrate a virtual machine\n"
					+ "2. Set cpu threshold\n"
					+ "3. Set migration threshold\n"
					+ "4. Get all clusters");
			choice = scanner.nextInt();
			switch(choice){
			case 0:
				printHostsAndVMs();
				break;
			case 1:
				migrate(scanner);
				break;
			case 2:
				setCPUThreshold(scanner);
				break;
			case 3:
				setMigrationThreshold(scanner);
				break;
			case 4:
				printDRSClusters();
				break;
			default:
				break;
			}
		}while(choice != 9);
		scanner.close();
	}
	
	public void closeConnection(){
		si.getServerConnection().logout();
	}
	
	public Algorithm(String IP, String login, String password){
		System.out.println("IP: " + IP);
		try{
			si = new ServiceInstance(new URL("https://" + IP + "/sdk"), login, password, true);
		} catch (RemoteException e){
			System.err.println("RemoteException: " + e.getMessage());
			System.exit(1);
		} catch (MalformedURLException e){
			System.err.println("MalformedURLException: " + e.getMessage());
			System.exit(1);
		}
	}
	
	public static void main(String[] args) throws Exception
	{
        Algorithm algorithm = new Algorithm(args[0], args[1], args[2]);
        algorithm.run();
        algorithm.closeConnection();

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