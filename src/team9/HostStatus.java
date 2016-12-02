package team9;

public class HostStatus {
	
	public HostStatus() {}
	
	public static void main(String[] args) throws Exception {
		
		Algorithm instance = new Algorithm(args[0], args[1], args[2]);
		
		while (System.in.available() == 0) {
			System.out.print("\033[H\033[2J");
			System.out.flush();
			instance.printHostsAndVMs();
			
			System.out.println("\n\nHit ENTER to quit");
			Thread.sleep(10000);
		}
		
		instance.closeConnection();
	}


}
