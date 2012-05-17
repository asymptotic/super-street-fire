package ca.site3.ssf.ioserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DeviceNetworkListener listens on a UDP socket for events from the game
 * peripherals (gloves, headsets). It passes the data to an {@link IDeviceDataParser}
 * which unpacks the data into {@link DeviceEvent}s. The events are then placed onto
 * a queue to be consumed.
 * 
 * @author greg
 */
public class DeviceNetworkListener implements Runnable {

	private Logger log = LoggerFactory.getLogger(getClass());
	
	private IDeviceDataParser dataParser;
	
	private int port;
	private DatagramSocket socket;
	
	private Queue<DeviceEvent> eventQueue;
	
	private volatile boolean stop = false;
	
	
	/**
	 * @param port the port to listen on
	 * @param dataParser an object that can translate raw data into higher-level {@link DeviceEvent}s
	 * @param q queue the {@link DeviceEvent}s will be placed on 
	 */
	public DeviceNetworkListener(int port, IDeviceDataParser dataParser, Queue<DeviceEvent> q) {
		this.port = port;
		this.dataParser = dataParser;
		this.eventQueue = q;
	}

	
	public void run() {
		stop = false;
		try {
			socket = new DatagramSocket(port);
		} catch (SocketException ex) {
			log.error("Unable to open UDP socket for listening on port "+port, ex);
			return;
		}
		
		int bufSize = 1024;
		byte receivedData[] = new byte[bufSize];
		DatagramPacket receivedPacket = new DatagramPacket(receivedData, bufSize);
		
		while ( ! stop ) {
			// reset buffer size
			receivedPacket.setLength(bufSize);
			try {
				socket.receive(receivedPacket);
			} catch (SocketTimeoutException ex) {
				log.debug("Device listener timed out");
			} catch (SocketException ex) {
				log.info("Device listener socket closed",ex);
				break;
			} catch (IOException ex) {
				log.warn("Exception receiving packet",ex);
			}
			
			try {
				byte[] data = Arrays.copyOfRange(receivedPacket.getData(), receivedPacket.getOffset(), 
						receivedPacket.getOffset()+receivedPacket.getLength());
				InetAddress address = receivedPacket.getAddress();
				List<? extends DeviceEvent> events = dataParser.parseDeviceData(data, address);
				for (DeviceEvent e : events) {
					log.debug("Created DeviceEvent: {}", e);
					if (e != null) {
						eventQueue.add(e);
					}
				}
			} catch (Exception ex) {
				log.warn("Could not parse packet data", ex);
			}
		}
		
		if ( ! socket.isClosed()) {
			socket.close();
		}
		
		log.info("device network listener exiting");
	}
	
	public void stop() {
		log.info("Stopping device network listener");
		this.stop = true;
		this.socket.close();
	}
}
