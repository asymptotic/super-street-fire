package ca.site3.ssf.ioserver;

import java.awt.Color;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.site3.ssf.common.Algebra;
import ca.site3.ssf.common.CommUtil;
import ca.site3.ssf.gamemodel.FireEmitterChangedEvent;
import ca.site3.ssf.gamemodel.IGameModel.Entity;
import ca.site3.ssf.gamemodel.PlayerActionPointsChangedEvent;
import ca.site3.ssf.gamemodel.PlayerHealthChangedEvent;
import ca.site3.ssf.gamemodel.RoundPlayTimerChangedEvent;
import ca.site3.ssf.gamemodel.SystemInfoRefreshEvent;
import ca.site3.ssf.gamemodel.SystemInfoRefreshEvent.OutputDeviceStatus;
import ca.site3.ssf.guiprotocol.StreetFireServer;

/**
 * Contains logic for converting game event data to the format expected by the
 * output hardware, and writes it out to the serial device.
 * 
 * Also stuff for sending query commands to the boards and awaiting their responses.
 * 
 * @author greg
 */
public class SerialCommunicator implements Runnable {

	private Logger log = LoggerFactory.getLogger(getClass());
	
	private final CommandLineArgs args;
	
	private static final byte[] STOP_SENTINEL = new byte[] { (byte)0 };
	private static final byte[] QUERY_SYSTEM_SENTINEL = new byte[] { (byte)'?' };
	
	private static final long STATUS_WAIT_TIME_MS = 100;
	
	private static final Color fullHealthColor = Color.GREEN;
	private static final Color noHealthColor = Color.RED;
	
	private static final Color timerColor = Color.ORANGE; // new Color(200, 200, 200);
	private static final Color endRoundTimerColor = new Color(255, 0, 0);
	
	private int lastTimerVal = 0;
	
	/** Number of LEDs on the strips for player health and action points */
	private static final int NUM_LIFE_BARS = 44;
	
	private static byte[] p1LifeBoardIds = new byte[] { 33, 36 };
	private static byte[] p2LifeBoardIds = new byte[] { 34, 37 };
	private static byte[] timerBoardIds  = new byte[] { 35, 38 };
	
	private static Set<Byte> lifeBoardIds = new HashSet<Byte>();
	static {
		for (byte b : p1LifeBoardIds) {
			lifeBoardIds.add(b);
		}
		for (byte b : p2LifeBoardIds) {
			lifeBoardIds.add(b);
		}
	}
	
	private static final byte[] MESSAGE_TEMPLATE_BYTES = new byte[] { 
		(byte) 0xAA, (byte) 0xAA, // framing bytes
		(byte) 2, // payload length (node ID and command)
		0, // destination node to be replaced within the loop
		(byte)63, // '?' command in ASCII
		0 // checksum to be replaced within the loop
	};
	
	private OutputDeviceStatus[] systemStatus = null;
	
	private OutputStream out;
	
	private BlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<byte[]>();
	
	private SerialDataReader reader;
	
	/** not ideal that this is here. used to fire system info refresh event to GUIs */
	private StreetFireServer server;
		
	private volatile boolean glowfliesOn = false;
	

	public SerialCommunicator(CommandLineArgs args, InputStream serialIn, OutputStream serialOut, StreetFireServer guiOut) {
		this.args = args;
		this.reader = new SerialDataReader(serialIn);
		this.out    = serialOut;
		this.server = guiOut;
	}
	
	/**
	 * Puts a sentinel value onto the message queue that will stop
	 * this SerialCommunicator from listening for events to
	 * broadcast out.
	 */
	public void stop() {
		messageQueue.add(STOP_SENTINEL);
	}
	
	public void ESTOP() {
		setGlowfliesOn(false);
	}
	
	public void run() {
		
		initializeStaticBoardColours();
		
		Thread inThread = new Thread(reader, "Serial input reader");
		inThread.start();
		
		while (true) {
			try {
				byte[] message = messageQueue.take();
				
				if (message.equals(STOP_SENTINEL)) {
					log.info("SerialCommunicator stopping");
					break;
				}
				else if (message.equals(QUERY_SYSTEM_SENTINEL)) {
					doSystemQuery();
				} 
				else {
					this.out.write(message);
					this.out.flush();					
					log.debug("wrote message: {}", CommUtil.bytesToHexString(message));
				}
				
			}
			catch (IOException ex) {
				log.error("Error writing to serial device",ex);
			}
			catch (InterruptedException ex) {
				log.warn("Interrupted trying to get message from queue",ex);
			}
		}
		setGlowfliesOn(false);
		reader.stop();
	}
	
	
	private void initializeStaticBoardColours() {
		for (byte boardId : timerBoardIds) {
			
			byte[] payload = new byte[] {
				boardId,
				(byte) 't',
				(byte) timerColor.getRed(),
				(byte) timerColor.getGreen(),
				(byte) timerColor.getBlue(),
			};
			
			byte[] msg = getMessageForPayload(payload);
			log.debug("Sending timer message: " + CommUtil.bytesToHexString(msg));
			enqueueMessage(msg);
			
			try {
				Thread.sleep(30);
			} catch (InterruptedException ex) {
				log.warn("wtf", ex);
			}
			
		}
		
		for (byte boardId : lifeBoardIds) {
			byte[] payload = new byte[] {
				boardId,
				(byte) 0x63,
				(byte) Color.BLUE.getRed(),
				(byte) Color.BLUE.getGreen(),
				(byte) Color.BLUE.getBlue()
			};
			log.debug("Enqueuing action points color message for board {}", boardId);
			enqueueMessage(getMessageForPayload(payload));
		}
	}

	
	/**
	 * Send messages to the serial port to trigger the flame effect.
	 * See https://code.google.com/p/super-street-fire/wiki/FlameEffectControllerProtocol
	 * 
	 * Each flame effect has 5 outputs: '1','2','3','4' for effect outputs (values from 0-100)
	 * 									'A' for AC output of hot surface igniter (values 0 or 1)
	 * 
	 * These outputs are controlled by sending one command per output (multiple commands
	 * can be in a message payload).
	 * 
	 * Note: we get one of these events for each emitter every tick, even if there's no actual change.
	 * 
	 * 
	 * @param event
	 */
	void notifyFireEmitters(FireEmitterChangedEvent event) {
		
		/*
		 * Message format: 0xAA 0xAA [payload_length] [ ... payload ... ] [payload_checksum]
		 * Payload for purposes of fire emitters is:
		 * [dest node] [command] [value]
		 * 
		 * dest node is easily obtainable, see getHardwareIdFromEvent
		 * command possibilities are n = ascii '1' - '4' corresponding to flame effect output n to [value] %
		 * 
		 * i'm not sure this mapping is correct but the way this is currently implemented is:
		 * 
		 * effect output 1 -> fire!
		 * effect output 2 -> player 1 colour
		 * effect output 3 -> player 2 colour
		 * effect output 4 -> currently unused
		 */
		
		// corresponds to effect outputs '1' to '4' (i _think_ this is meant to be ASCII)
		byte[] commands = new byte[] { (byte)49,(byte)50,(byte)51,(byte)52 }; 
		byte[] values = new byte[] { 0, 0, 0, 0 }; // the values for the commands
		
		if (event.getContributingEntities().size() > 0) {
			values[0] = (byte)(100 * event.getMaxIntensity()); // other option is to sum intensities
			
			if (event.getContributingEntities().contains(Entity.PLAYER1_ENTITY)) {
				values[1] = (byte) (100 * event.getIntensity(Entity.PLAYER1_ENTITY));
			}
			if (event.getContributingEntities().contains(Entity.PLAYER2_ENTITY)) {
				values[2] = (byte) (100 * event.getIntensity(Entity.PLAYER2_ENTITY));
			}
		}
		
		byte[] payload = new byte[11]; // 1 byte for dest node + 4*2 bytes command/values + 2 bytes for HSI 
		payload[0] = getHardwareIdFromEvent(event);
		
		for (int effectOutputIndex=0; effectOutputIndex < 4; effectOutputIndex++) {
			payload[effectOutputIndex*2 + 1] = commands[effectOutputIndex];
			payload[effectOutputIndex*2 + 2] = values[effectOutputIndex];
		}
		payload[9] = (byte) 0x41; // 'A' for A/C out (hot surface igniter)
		if (glowfliesOn) {
			payload[10] = (byte) 0x31; // '1' is ascii 31
		} else {
			payload[10] = (byte) 0x30; // '0' is ascii 30
		}
		enqueueMessage(getMessageForPayload(payload));
	}
	
	
	public void setGlowfliesOn(boolean makeSurfaceHot, boolean broadcast) {
		log.info(makeSurfaceHot ? "Turning glowflies on." : "Turning glowflies off.");
		this.glowfliesOn = makeSurfaceHot;
		
		if (broadcast) {
			byte[] payload = new byte[3];
			payload[0] = (byte) 0xFF; // broadcast
			payload[1] = (byte) 0x41; // 'A' for A/C
			if (glowfliesOn) {
				payload[2] = (byte) 0x31; // '1' in ascii
			} else {
				payload[2] = (byte) 0x30; // '0'
			}
			enqueueMessage(getMessageForPayload(payload));
		} else {
			for (int i=1; i<=32; i++) {
				toggleGlowfly(glowfliesOn, i);
			}
		}
	}
	
	
	public void toggleGlowfly(boolean on, int id) {
		byte[] payload = new byte[3];
		payload[0] = (byte)id;
		payload[1] = (byte) 0x41; // 'A' for A/C
		if (on) {
			payload[2] = (byte) 0x31; // '1' in ascii
		} else {
			payload[2] = (byte) 0x30; // '0'
		}
		enqueueMessage(getMessageForPayload(payload));
	}
	
	
	public void setGlowfliesOn(boolean makeSurfaceHot) {
		setGlowfliesOn(makeSurfaceHot, true);
	}
	
	
	private byte[] getMessageForPayload(byte[] payload) {
		byte[] message = new byte[payload.length + 4];
		message[0] = message[1] = (byte) 0xAA; // framing bytes
		message[2] = (byte)payload.length;
		for (int i=0; i < payload.length; i++) {
			message[i+3] = payload[i];
		}
		message[payload.length + 3] = getChecksum(payload);
		return message;
	}
	
	void onPlayerHealthChanged(PlayerHealthChangedEvent e) {
		short life;
		if (e.getPlayerNum() == 1) {
			life = (short)e.getNewLifePercentage();
		} else if (e.getPlayerNum() == 2) {
			life = (short)e.getNewLifePercentage();
		} else {
			throw new IllegalArgumentException("Invalid player number: " + e.getPlayerNum());
		}
		Color c = getColorFromLifePercentage(life);
		// need to send life amount ('L') and colour ('l') for life bars
		for (int boardId : (e.getPlayerNum() == 1 ? p1LifeBoardIds : p2LifeBoardIds)) {
			byte[] payload;
			
			payload = new byte[] {
				(byte) boardId,
				(byte) 0x4c,
				(byte) Math.round(life / 100.0 * NUM_LIFE_BARS),
				(byte) 0x6c,
				(byte) c.getRed(),
				(byte) c.getGreen(),
				(byte) c.getBlue(),
			};
			byte[] msg = getMessageForPayload(payload);
			log.debug("Enqueuing player {} life ({}) percentage message for board {}: {}", new Object[] {e.getPlayerNum(), life, boardId, CommUtil.bytesToHexString(msg)});
			enqueueMessage(msg);
			
			
//			try {
//				Thread.sleep(20);
//			} catch (InterruptedException ex) {
//				log.warn("Sleep between health level and colour messages interrupted", ex);
//			}
			
		}
	}
	
	private Color getColorFromLifePercentage(short lifePercentage) {
		return Algebra.colorLerp(noHealthColor, fullHealthColor, ((float)lifePercentage) / 100.0f);
	}
	
	
	void onPlayerActionPointsChanged(PlayerActionPointsChangedEvent e) {
		short points;
		if (e.getPlayerNum() == 1) {
			points = (short)e.getNewActionPointAmt();
		} else if (e.getPlayerNum() == 2) {
			points = (short)e.getNewActionPointAmt();
		} else {
			throw new IllegalArgumentException("Invalid player number: " + e.getPlayerNum());
		}
		// need to send action points amount ('C') and color ('c')
		for (byte boardId : (e.getPlayerNum() == 1 ? p1LifeBoardIds : p2LifeBoardIds)) {
			byte[] payload = new byte[] {
				boardId,
				(byte) 0x43,
				(byte) Math.round(points / 100.0 * NUM_LIFE_BARS)
			};
			log.debug("Enqueuing action points percentage message for board {}", boardId);
			enqueueMessage(getMessageForPayload(payload));
		}
	}
	

	void onTimerChanged(RoundPlayTimerChangedEvent e) {
		if (e.getTimeInSecs() < 0 || e.getTimeInSecs() > this.args.roundTimeInSecs) {
			log.warn("Unsupported timer value {}", e.getTimeInSecs());
			return;
		}

		this.onTimerChanged(e.getTimeInSecs());
	}
	
	void onTimerChanged(int timerVal) {
		
		this.lastTimerVal = timerVal;
		Color c = this.lastTimerVal <= 10 ? timerColor : endRoundTimerColor;
		
		for (byte boardId : timerBoardIds) {
						
			byte[] payload = new byte[] {
				boardId,
				(byte) 'T',
				(byte) lastTimerVal
			};
			
			byte[] msg = getMessageForPayload(payload);
			log.debug("Sending timer {} message: {}", boardId, CommUtil.bytesToHexString(msg));
			enqueueMessage(msg);
			
			payload = new byte[] {
				boardId,
				(byte) 't',
				(byte) c.getRed(),
				(byte) c.getGreen(),
				(byte) c.getBlue(),
			};
			msg = getMessageForPayload(payload);
			log.debug("Sending timer message: " + CommUtil.bytesToHexString(msg));
			enqueueMessage(msg);
		}
	}
	
	
	/**
	 * Sends out a query command to the devices one at a time.
	 * Note! this will hold up the serial comm thread so it should not be
	 * done while a game is actually going on.
	 */
	void querySystemStatus() {
		enqueueMessage( QUERY_SYSTEM_SENTINEL ); // sentinel value to trigger system query
	}
	
	/**
	 * Queries the board with the given number and returns its status.
	 * @param boardNumber The board to query.
	 * @return The resulting status of the board.
	 */
	public OutputDeviceStatus queryBoard(int boardNumber) {
		byte[] messageTemplate = MESSAGE_TEMPLATE_BYTES.clone();
		return this.queryBoard(boardNumber, messageTemplate);
	}
	
	public OutputDeviceStatus[] queryAllBoards() {
		OutputDeviceStatus[] result = new OutputDeviceStatus[32]; // indexes are off-by-one compared to device IDs
		for (int i = 0; i < 32; i++) {
			result[i] = null;
		}
		
		// sent out to each emitter in turn
		byte[] messageTemplate = MESSAGE_TEMPLATE_BYTES.clone();
		
		this.reader.clear();
		for (int i = 1; i <= 32; i++) {
			OutputDeviceStatus status = this.queryBoard(i, messageTemplate);
			assert(status != null);
			if (status != null) {
				result[status.deviceId - 1] = status;
			}
		}
		
		return result;
	}
	
	/**
	 * Helper function that queries the board with the given number,
	 * also has a cached message template for efficiency when querying all boards at once.
	 * @param boardNumber The board to query.
	 * @param messageTemplate The message template bytes, must conform to MESSAGE_TEMPLATE.
	 * @return The resulting status of the board.
	 */
	private OutputDeviceStatus queryBoard(int boardNumber, byte[] messageTemplate) {
		
		OutputDeviceStatus systemStatus = new OutputDeviceStatus(boardNumber, false, false, false);
		
		this.reader.clear();
		messageTemplate[3] = (byte)boardNumber;
		messageTemplate[5] = (byte)~(boardNumber + 63);
		
		try {
			this.out.write(messageTemplate);
			this.out.flush();
			
			try {
				OutputDeviceStatus result = reader.getStatusUpdateQueue().poll(STATUS_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
				if (result != null) {
					systemStatus = result;
				}
			}
			catch (InterruptedException ex) {
				log.warn("Interrupted while waiting for device status",ex);
			}
		
		}
		catch (IOException ex) {
			log.error("Error reading or writing status query for node", ex);
		}
		
		return systemStatus;
	}
	
	
	/**
	 * Queries each emitter sequentially for its status. Waits a bit
	 * for a response then moves to the next if nothing after 100 ms.
	 * 
	 * This method gets called on the comm thread, so it
	 * directly writes to the serial out stream.
	 */
	private void doSystemQuery() {
		
		log.info("Querying boards...");
		this.systemStatus = this.queryAllBoards();
		
		/*
		 * Convert from device ID to GameModel. Inverse of getHardwareIdFromEvent
		 */
		OutputDeviceStatus[] leftRailStatus = new OutputDeviceStatus[8];
		for (int i=0; i<8; i++) {
			leftRailStatus[i] = this.systemStatus[8+i];
		}
		OutputDeviceStatus[] rightRailStatus = new OutputDeviceStatus[8];
		for (int i=0; i<8; i++) {
			rightRailStatus[i] = this.systemStatus[i];
		}
		OutputDeviceStatus[] outerRingStatus = new OutputDeviceStatus[16];
		for (int i=0; i<8; i++) {
			outerRingStatus[i] = this.systemStatus[16+i];
		}
		for (int i=8; i<16; i++) {
			outerRingStatus[i] = this.systemStatus[31-(i-8)];
		}
		
		/*
		 * The organization of this code is not ideal. For convenience,
		 * SystemInfoRefreshEvent implements IGameModelEvent even though it
		 * doesn't originate from the GameModel. I'm leaving it that way for
		 * now because I can't bear the thought of adding yet another queue
		 * somewhere. Only the GUIs get notified of this, not any other
		 * IGameModelListeners
		 */
		SystemInfoRefreshEvent refreshEvent = 
				new SystemInfoRefreshEvent(leftRailStatus, rightRailStatus, outerRingStatus);
		if (server != null) {
			server.notifyGUI(refreshEvent);
		}
		log.info("...done querying boards and notifying GUI(s)");
	}
	
	
	
	
	/**
	 * The hardware device ID layout is as follows:
	 * https://code.google.com/p/super-street-fire/wiki/FlameEffectControllerProtocol
	 * <pre>
	 *                             QP1
	 *                 17                          25
	 *             18             33 35               26
	 *     ---------                                 ------------
	 *                       1             9
	 *
	 *         19            2             10             27
	 *         
	 *                       3             11
	 *    | 20                                                28  |
	 *    |                  4             12                     |
	 * QF |                                                       | QBack
	 *    |                  5             13                     |
	 *    | 21                                                29  |
	 *                       6             14
	 *         
	 *         22            7             15             30
	 *             
	 *                       8             16
	 *     ----------                                 ------------
	 *             23                                 31
	 *                            34 36            
	 *                 24                        32
	 *                             QP2
	 * </pre>
	 * 
	 * <p>33 -> P1 air cannon / platform lights<br />
	 *    34 -> P2 air cannon / platform lights<br />
	 *    35 -> P1 life bars / timer<br />
	 *    36 -> P1 life bars / timer
	 * </p>
	 * 
	 * FireEmitterModel (package-private class) is where the GameModel IDs are defined:<br>
	 * 
	 * The outer ring of emitters start at the first emitter to the right
	 *   of player one's back and moving in a counter-clockwise direction.<br>
	 *   --> This starts at 17 and goes up to 24 (0-7 in FireEmitterModel terms) , 
	 *       then goes backwards from 32 to 25 for FireEmitterModel indexes 8-15
	 * <br />
	 * Left rail is the rail of emitters on the left from player one's perspective,
	 *	 they are ordered starting at player one going towards player two.<br>
	 * <br />
	 * Right rail is the rail of emitters on the right from player one's perspective,
	 *   they are ordered starting at player one going towards player two<br>
	 * 
	 * Note GameModel indexes are 0-based and per-rail or per-outer ring
	 * 
	 * @param e
	 * @return
	 */
	private static byte getHardwareIdFromEvent(FireEmitterChangedEvent e) {
		
		switch (e.getLocation()) {
		case RIGHT_RAIL:
			return (byte) (1 + e.getIndex());
		case LEFT_RAIL:
			return (byte) (9 + e.getIndex());
		case OUTER_RING:
			if (e.getIndex() <=7) {
				return (byte) (17 + e.getIndex());
			} else {
				// e.getIndex will be from 8 to 15 corresponding to 32 to 25
				// 8->32, 9-31, 10->30, 11->29, 12->28, 13->27, 14->26, 15->25
				return (byte) (32 + (8 - e.getIndex()));
			}
		}
		throw new IllegalArgumentException("Can't deal with FireEmitterChangedEvent: "+e);
	}
	
	
	
	
	/**
	 * The checksum is one byte and computed by summing each of the payload bytes, 
	 * then taking the one's complement of the sum.
	 * 
	 * @param payload
	 * @return
	 */
	static byte getChecksum(byte[] payload) {
		/*
		 *  Note: bytes are signed in Java and represented as 2's complement.
		 *  We'll sum with a byte and let it roll over (could also use an int and 
		 *  use the least significant byte).
		 */
		byte sum = 0;
		for (byte b : payload) {
			sum += b;
		}
		return (byte) ~sum; // tilde operator flips each bit (aka 1's complement) 
	}
	
	
	/**
	 * Places a message on the queue to be sent over the serial interface.
	 * @param message
	 */
	private void enqueueMessage(byte[] message) {
		if (this.messageQueue.offer(message) == false) {
			log.warn("No room on queue for serial message");
		}
		
		/*
		if (message.length > 5 && lifeBoardIds.contains(message[4])) {
			try {
				Thread.sleep(10);
			} 
			catch (InterruptedException ex) {
				log.warn("Interrupted while pausing after sending life board message", ex);
			}
		}
		*/
	}
	
}
