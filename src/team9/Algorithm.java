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
        //ResourcePool rp = (ResourcePool) new InventoryNavigator(rootFolder).searchManagedEntity("ResourcePool", "Resources");
        ManagedEntity[] resp = new InventoryNavigator(rootFolder).searchManagedEntities("ResourcePool");
        ResourcePool rp = (ResourcePool)resp[0];
        System.out.println("RESP = " + rp.getName());
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
			System.out.println("VM Host 1 = " + hs1.getName());
			System.out.println("VM Host 2 = " + hs2.getName());
			Datastore[] ds1 = hs1.getDatastores();
			Datastore[] ds2 = hs2.getDatastores();
			for (int j = 0; j < ds1.length; j++)
			{
				System.out.println("DS1 = " + ds1[j].getName());
			}
			VirtualMachine[] vm = new VirtualMachine[meVM.length];
			for (ManagedEntity me: meVM)
			{
				vm[i++] = (VirtualMachine) me;
			}
			System.out.println("VM 1 = " + vm[0].getName());
			System.out.println("VM 2 = " + vm[1].getName());
			//System.out.println("resource pool = " + rp.getName());
			VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec() ;
			VirtualMachineRelocateSpec relocate = new VirtualMachineRelocateSpec() ;
			cloneSpec.setLocation(relocate) ;
			cloneSpec.setPowerOn(false) ;
			cloneSpec.setTemplate(false) ;
			Task task = vm[0].cloneVM_Task((Folder)vm[0].getParent() ,"CloneTestVM", cloneSpec);
			VirtualMachineRelocateSpec relocate1 = new VirtualMachineRelocateSpec() ;
			relocate1.setDatastore(ds1[1].getMOR());
			relocate1.setHost(hs1.getMOR());
			//Task task = vm[0].cloneVM_Task((Folder)vm[0].getParent() ,"CloneTestVM1", cloneSpec);
			/*Task task = vm[0].migrateVM_Task(rp, hs1,VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOn);
			   String[] checks = new String[] {"cpu", "software"};
			    HostVMotionCompatibility[] vmcs =
			      si.queryVMotionCompatibility(vm[0], new HostSystem[]
			         {hs1},checks );
			    String[] comps = vmcs[0].getCompatibility();
			    if(checks.length != comps.length)
			    {
			      System.out.println("CPU/software NOT compatible. Exit.");
			    }
			    else
			    {
			    	System.out.println("Success");
			    	Task task = vm[1].migrateVM_Task(rp, hs1,VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOn);
			    	   if(task.waitForTask()==Task.SUCCESS)
			    	    {
			    	      System.out.println("VMotioned!");
			    	    }
			    	    else
			    	    {
			    	      System.out.println("VMotion failed!");
			    	      TaskInfo info = task.getTaskInfo();
			    	      System.out.println(info.getError().getFault());
			    	    }
			    }*/

		}

        //if (me == null) {
        //	return;
        //}
        //HostSystem host = (HostSystem) me;
        //int usedCpu = host.getSummary().getQuickStats().getOverallCpuUsage();
        //System.out.println("Host: " + host.getName() + ", CPU usage: " + usedCpu);
        si.getServerConnection().logout();
	}
}
