package team9;
import java.net.URL;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import java.rmi.RemoteException;


public class Algorithm {
	public static void main(String[] args) throws Exception
	{
		ServiceInstance si = new ServiceInstance(new URL("https://" + args[0] + "/sdk"), args[1], args[2], true);
        Folder rootFolder = si.getRootFolder();
        ResourcePool rp = (ResourcePool) new InventoryNavigator(rootFolder).searchManagedEntity("ResourcePool", "Resources");
      //  System.out.println("RESP = " + rp.getName());
        //String hostname = "130.65.159.153";
        //ManagedEntity me = new InventoryNavigator(rootFolder).searchManagedEntity("HostSystem", hostname);
        ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");	
		for(int i = 0; i < mes.length; i++){
			HostSystem hs = (HostSystem)mes[i];
			int usedCpu = hs.getSummary().getQuickStats().getOverallCpuUsage();
			System.out.println("Host: " + hs.getName() + ", CPU usage: " + usedCpu);
		}
		int i = 0;
		ManagedEntity[] meVM = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
		if (meVM == null || meVM.length == 0) 
			System.out.println("No virtual machine!");
		else
		{
			HostSystem hs1 = (HostSystem)mes[0];
			HostSystem hs2 = (HostSystem)mes[1];
			HostSystem hs3 = (HostSystem)mes[2];
			VirtualMachine[] vm = new VirtualMachine[meVM.length];
			for (ManagedEntity me: meVM)
			{
				vm[i++] = (VirtualMachine) me;
				System.out.println("VM " + i + " = " + vm[i-1].getName());
			}
		}
        si.getServerConnection().logout();
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
	public static boolean checkCompatible(ServiceInstance si, VirtualMachine vm, HostSystem host) throws RuntimeFault, RemoteException
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
