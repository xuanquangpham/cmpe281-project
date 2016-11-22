import java.net.URL;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;

import java.rmi.RemoteException;
import java.util.Scanner;

public class Algorithm {
	public static void main(String[] args) throws Exception
	{
		ServiceInstance si = new ServiceInstance(new URL("https://" + args[0] + "/sdk"), args[1], args[2], true);
        Folder rootFolder = si.getRootFolder();
        
        String hostname = "130.65.159.153";
        ManagedEntity me = new InventoryNavigator(rootFolder).searchManagedEntity("HostSystem", hostname);

        if (me == null) {
        	return;
        }
        HostSystem host = (HostSystem) me;
        int usedCpu = host.getSummary().getQuickStats().getOverallCpuUsage();
        System.out.println("Host: " + host.getName() + ", CPU usage: " + usedCpu);
        si.getServerConnection().logout();
	}
}
