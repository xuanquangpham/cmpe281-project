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
	Integer migrationThreshold;
	static String vmNamePowerOn = null;
	
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
			System.out.println("Host[" + i + "]: " + hs.getName() + ", <<CPU usage: " + getHostCpuUsagePecentage(hs) + "%>>");
			VirtualMachine[] vms = hs.getVms();
			for(int j = 0 ; j < vms.length; j++){
				int numOfCores = hs.getSummary().getHardware().getNumCpuCores();
				int cpuMhz = hs.getSummary().getHardware().getCpuMhz();
				int usedCpuMhz = vms[j].getSummary().getQuickStats().getOverallCpuUsage();
				double percent = (usedCpuMhz / (double)(numOfCores * cpuMhz)) * 100;
				
				DecimalFormat df = new DecimalFormat("####0.00");
				System.out.println("   + VM[" + j + "]: <" + vms[j].getName() + "> - Power state: " + vms[j].getRuntime().getPowerState()
						+ ", CPU usage: " + Double.valueOf(df.format(percent)) + "%");
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
	
	
	private void setMigrationThreshold(Scanner scanner, DRSRunnable drsRunnable){
		System.out.print("Enter migration threshold: [1-6] (1 being more conservative, 6 being more aggressive: ");
		migrationThreshold = scanner.nextInt();
		drsRunnable.setMigrationThreshold(migrationThreshold);
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
			HostSystem hss[] = cluster.getHosts();
			double[] hostCPUUsage = new double[hss.length];
			for(int j = 0; j < hss.length; j++){
				hostCPUUsage[j] = getHostCpuUsagePecentage(hss[j]);
				hostCPUUsage[j] /= 1000;
				System.out.println("Host[" + j + "] cpu usage: " + hostCPUUsage[j]);
			}
			System.out.println("Calculated sd: " + calculateSampleSD(hostCPUUsage));
		}
	}
	
	private void run() throws RemoteException, InterruptedException {
		DRSRunnable drsRunnable = null;
		DRSPowerManagement drsPM = null;
		Integer choice;
		Scanner scanner = new Scanner(System.in);
		do { 
			System.out.println("1. Print all hosts and their respective virtual machines\n"
					+ "2. Power On a Virtual Machine\n"
					+ "3. Migrate a virtual machine\n"
					+ "4. Get all clusters\n"
					+ "5. Set migration threshold\n"
					+ "6. Start DRS Load Balancing\n"
					+ "7. Start DRS Power Management\n"
					+ "0. Exit");
			System.out.print("> ");
			choice = scanner.nextInt();
			switch(choice){
			case 1:
				printHostsAndVMs();
				break;
			case 3:
				migrate(scanner);
				break;
			case 4:
				printDRSClusters();
				break;
			case 5:
				if(drsRunnable != null)
					setMigrationThreshold(scanner, drsRunnable);
				break;
			case 2:
				System.out.println("Enter VM name from the cluster list to power on (not VM's position)\n"
						+ "This will kick off DRS Load Balancing mode");
				scanner = new Scanner(System.in);
				vmNamePowerOn = scanner.nextLine();
				Thread.sleep(10000);
			case 6:
				drsRunnable = new DRSRunnable(si);
				Thread drsThread = new Thread(drsRunnable);
				drsThread.start();
				System.out.println("Enter q to stop DRS Load Balancing");
				String s;
				do {
					s = scanner.nextLine();
				} while(!s.equals("q"));
				drsRunnable.terminate();
				drsThread.join();
				break;
			case 7:
				drsPM = new DRSPowerManagement(si);
				Thread drsPMThread = new Thread(drsPM);
				drsPMThread.start();
				System.out.println("Enter q to stop DRS Power Management");
				do {
					s = scanner.nextLine();
				} while(!s.equals("q"));
				drsPM.terminate();
				drsPMThread.join();
				break;				
			default:
				break;
			}
		} while(choice != 0);
		scanner.close();
	}

	public void closeConnection() {
		si.getServerConnection().logout();
	}
	
	public Algorithm(String IP, String login, String password) {

		try {
			si = new ServiceInstance(new URL("https://" + IP + "/sdk"), login, password, true);
		} catch (RemoteException e){
			System.err.println("RemoteException: " + e.getMessage());
			System.exit(1);
		} catch (MalformedURLException e) {
			System.err.println("MalformedURLException: " + e.getMessage());
			System.exit(1);
		}
	}
	
	public static void main(String[] args) throws Exception {
		Algorithm algorithm = new Algorithm(args[0], args[1], args[2]);
		algorithm.run();
        algorithm.closeConnection();
	}
	
	public boolean migrateVM(ResourcePool rp, VirtualMachine vm, HostSystem host) throws VmConfigFault, Timedout, FileFault, InvalidState, InsufficientResourcesFault, MigrationFault, RuntimeFault, RemoteException, InterruptedException {
		Task task = vm.migrateVM_Task(rp, host,VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOn);
		
		if(task.waitForTask()==Task.SUCCESS) {
			return true;
		} else {
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
