package com.limelight.nvstream.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.ConnectionStatusListener;

public class ControlStream implements ConnectionStatusListener {
	
	public static final int PORT = 47995;
	
	public static final int CONTROL_TIMEOUT = 5000;
	
	public static final short PTYPE_START_STREAM_A = 0x140b;
	public static final short PPAYLEN_START_STREAM_A = 1;
	public static final byte[] PPAYLOAD_START_STREAM_A = new byte[]{0};
	
	public static final short PTYPE_START_STREAM_B = 0x1410;
	public static final short PPAYLEN_START_STREAM_B = 16;
	
	public static final short PTYPE_RESYNC = 0x1404;
	public static final short PPAYLEN_RESYNC = 24;
	
	public static final short PTYPE_LOSS_STATS = 0x140c;
	public static final short PPAYLEN_LOSS_STATS = 20;
	
	// Currently unused
	public static final short PTYPE_FRAME_STATS = 0x1417;
	public static final short PPAYLEN_FRAME_STATS = 64;
	
	public static final int LOSS_REPORT_INTERVAL_MS = 50;
	
	private int currentFrame;
	private int lossCountSinceLastReport;
	
	private NvConnectionListener listener;
	private InetAddress host;
	
	public static final int LOSS_PERIOD_MS = 15000;
	public static final int MAX_LOSS_COUNT_IN_PERIOD = 2;
	public static final int MAX_SLOW_SINK_COUNT = 2;
	public static final int MESSAGE_DELAY_FACTOR = 3;
	
	private long lossTimestamp;
	private int lossCount;
	private int slowSinkCount;
	
	private Socket s;
	private InputStream in;
	private OutputStream out;
	
	private Thread lossStatsThread;
	private Thread resyncThread;
	private LinkedBlockingQueue<int[]> invalidReferenceFrameTuples = new LinkedBlockingQueue<int[]>();
	private boolean aborting = false;
	
	public ControlStream(InetAddress host, NvConnectionListener listener)
	{
		this.listener = listener;
		this.host = host;
	}
	
	public void initialize() throws IOException
	{
		s = new Socket();
		s.setTcpNoDelay(true);
		s.connect(new InetSocketAddress(host, PORT), CONTROL_TIMEOUT);
		in = s.getInputStream();
		out = s.getOutputStream();
	}
	
	private void sendPacket(NvCtlPacket packet) throws IOException
	{
		out.write(packet.toWire());
		out.flush();
	}
	
	private ControlStream.NvCtlResponse sendAndGetReply(NvCtlPacket packet) throws IOException
	{
		sendPacket(packet);
		return new NvCtlResponse(in);
	}
	
	private void sendLossStats() throws IOException
	{
		ByteBuffer bb = ByteBuffer.allocate(PPAYLEN_LOSS_STATS).order(ByteOrder.LITTLE_ENDIAN);
		
		bb.putInt(lossCountSinceLastReport); // Packet loss count
		bb.putInt(LOSS_REPORT_INTERVAL_MS); // Time since last report in milliseconds
		bb.putInt(1000);
		bb.putLong(currentFrame); // Last successfully received frame

		sendPacket(new NvCtlPacket(PTYPE_LOSS_STATS, PPAYLEN_LOSS_STATS, bb.array()));
	}
	
	public void abort()
	{
		if (aborting) {
			return;
		}
		
		aborting = true;
		
		try {
			s.close();
		} catch (IOException e) {}
		
		if (lossStatsThread != null) {
			lossStatsThread.interrupt();
			
			try {
				lossStatsThread.join();
			} catch (InterruptedException e) {}
		}
		
		if (resyncThread != null) {
			resyncThread.interrupt();
			
			try {
				resyncThread.join();
			} catch (InterruptedException e) {}
		}
	}
	
	public void start() throws IOException
	{
		// Use a finite timeout during the handshake process
		s.setSoTimeout(CONTROL_TIMEOUT);
		
		doStartA();
		doStartB();
		
		// Return to an infinte read timeout after the initial control handshake
		s.setSoTimeout(0);
		
		lossStatsThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					try {
						sendLossStats();
						lossCountSinceLastReport = 0;
					} catch (IOException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					try {
						Thread.sleep(LOSS_REPORT_INTERVAL_MS);
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
				}
			}
		};
		lossStatsThread.setName("Control - Loss Stats Thread");
		lossStatsThread.start();
		
		resyncThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					int[] tuple;
					
					// Wait for a tuple
					try {
						tuple = invalidReferenceFrameTuples.take();
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					// Aggregate all lost frames into one range
					int[] lastTuple = null;
					for (;;) {
						int[] nextTuple = lastTuple = invalidReferenceFrameTuples.poll();
						if (nextTuple == null) {
							break;
						}
						
						lastTuple = nextTuple;
					}
					
					// The server expects this to be the firstLostFrame + 1
					tuple[0]++;
					
					// Update the end of the range to the latest tuple
					if (lastTuple != null) {
						tuple[1] = lastTuple[1];
					}
					
					try {
						LimeLog.warning("Invalidating reference frames from "+tuple[0]+" to "+tuple[1]);
						ControlStream.this.sendResync(tuple[0], tuple[1]);
						LimeLog.warning("Frames invalidated");
					} catch (IOException e) {
						listener.connectionTerminated(e);
						return;
					}
				}
			}
		};
		resyncThread.setName("Control - Resync Thread");
		resyncThread.start();
	}
	
	private ControlStream.NvCtlResponse doStartA() throws IOException
	{
		return sendAndGetReply(new NvCtlPacket(PTYPE_START_STREAM_A,
				PPAYLEN_START_STREAM_A, PPAYLOAD_START_STREAM_A));
	}
	
	private ControlStream.NvCtlResponse doStartB() throws IOException
	{
		ByteBuffer payload = ByteBuffer.wrap(new byte[PPAYLEN_START_STREAM_B]).order(ByteOrder.LITTLE_ENDIAN);
		
		payload.putInt(0);
		payload.putInt(0);
		payload.putInt(0);
		payload.putInt(0xa);
		
		return sendAndGetReply(new NvCtlPacket(PTYPE_START_STREAM_B, PPAYLEN_START_STREAM_B, payload.array()));
	}
	
	private void sendResync(int firstLostFrame, int nextSuccessfulFrame) throws IOException
	{
		ByteBuffer conf = ByteBuffer.wrap(new byte[PPAYLEN_RESYNC]).order(ByteOrder.LITTLE_ENDIAN);
		
		//conf.putLong(firstLostFrame);
		//conf.putLong(nextSuccessfulFrame);
		conf.putLong(0);
		conf.putLong(0xFFFFF);
		conf.putLong(0);
		
		sendAndGetReply(new NvCtlPacket(PTYPE_RESYNC, PPAYLEN_RESYNC, conf.array()));
	}
	
	class NvCtlPacket {
		public short type;
		public short paylen;
		public byte[] payload;
		
		public NvCtlPacket(InputStream in) throws IOException
		{
			byte[] header = new byte[4];
			
			int offset = 0;
			do
			{
				int bytesRead = in.read(header, offset, header.length - offset);
				if (bytesRead < 0) {
					break;
				}
				offset += bytesRead;
			} while (offset != header.length);
			
			if (offset != header.length) {
				throw new IOException("Socket closed prematurely");
			}
			
			ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			
			type = bb.getShort();
			paylen = bb.getShort();
			
			if (paylen != 0)
			{
				payload = new byte[paylen];

				offset = 0;
				do
				{
					int bytesRead = in.read(payload, offset, payload.length - offset);
					if (bytesRead < 0) {
						break;
					}
					offset += bytesRead;
				} while (offset != payload.length);
				
				if (offset != payload.length) {
					throw new IOException("Socket closed prematurely");
				}
			}
		}
		
		public NvCtlPacket(byte[] payload)
		{
			ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
			
			type = bb.getShort();
			paylen = bb.getShort();
			
			if (bb.hasRemaining())
			{
				payload = new byte[bb.remaining()];
				bb.get(payload);
			}
		}
		
		public NvCtlPacket(short type, short paylen)
		{
			this.type = type;
			this.paylen = paylen;
		}
		
		public NvCtlPacket(short type, short paylen, byte[] payload)
		{
			this.type = type;
			this.paylen = paylen;
			this.payload = payload;
		}
		
		public short getType()
		{
			return type;
		}
		
		public short getPaylen()
		{
			return paylen;
		}
		
		public void setType(short type)
		{
			this.type = type;
		}
		
		public void setPaylen(short paylen)
		{
			this.paylen = paylen;
		}
		
		public byte[] toWire()
		{
			ByteBuffer bb = ByteBuffer.allocate(4 + (payload != null ? payload.length : 0)).order(ByteOrder.LITTLE_ENDIAN);
			
			bb.putShort(type);
			bb.putShort(paylen);
			
			if (payload != null)
				bb.put(payload);
			
			return bb.array();
		}
	}
	
	class NvCtlResponse extends NvCtlPacket {
		public short status;
		
		public NvCtlResponse(InputStream in) throws IOException {
			super(in);
		}
		
		public NvCtlResponse(short type, short paylen) {
			super(type, paylen);
		}
		
		public NvCtlResponse(short type, short paylen, byte[] payload) {
			super(type, paylen, payload);
		}
		
		public NvCtlResponse(byte[] payload) {
			super(payload);
		}
		
		public void setStatusCode(short status)
		{
			this.status = status;
		}
		
		public short getStatusCode()
		{
			return status;
		}
	}

	public void connectionTerminated() {
		abort();
	}

	private void resyncConnection(int firstLostFrame, int nextSuccessfulFrame) {
		invalidReferenceFrameTuples.add(new int[]{firstLostFrame, nextSuccessfulFrame});
	}

	public void connectionDetectedFrameLoss(int firstLostFrame, int nextSuccessfulFrame) {
		if (System.currentTimeMillis() > LOSS_PERIOD_MS + lossTimestamp) {
			lossCount++;
			lossTimestamp = System.currentTimeMillis();
		}
		else {
			if (++lossCount == MAX_LOSS_COUNT_IN_PERIOD) {
				listener.displayTransientMessage("Detected high amounts of network packet loss. " +
						"Try improving your network connection or lowering stream resolution, frame rate, and/or bitrate. " +
						"Use a 5 GHz wireless connection if available and connect your PC directly to your router via Ethernet if possible.");
				lossCount = -MAX_LOSS_COUNT_IN_PERIOD * MESSAGE_DELAY_FACTOR;
				lossTimestamp = 0;
			}
		}
		
		resyncConnection(firstLostFrame, nextSuccessfulFrame);
	}

	public void connectionSinkTooSlow(int firstLostFrame, int nextSuccessfulFrame) {
		if (++slowSinkCount == MAX_SLOW_SINK_COUNT) {
			listener.displayTransientMessage("Your device is processing the A/V data too slowly. Try lowering stream resolution and/or frame rate.");
			slowSinkCount = -MAX_SLOW_SINK_COUNT * MESSAGE_DELAY_FACTOR;
		}
		
		resyncConnection(firstLostFrame, nextSuccessfulFrame);
	}

	public void connectionReceivedFrame(int frameIndex) {
		currentFrame = frameIndex;
	}

	public void connectionLostPackets(int lastReceivedPacket, int nextReceivedPacket) {
		// Update the loss count for the next loss report
		lossCountSinceLastReport += (nextReceivedPacket - lastReceivedPacket) - 1;
	}
}
