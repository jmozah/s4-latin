package io.s4.latin.adapter;

import io.s4.collector.EventWrapper;
import io.s4.latin.pojo.StreamRow;
import io.s4.latin.pojo.StreamRow.ValueType;
import io.s4.listener.EventHandler;
import io.s4.listener.EventProducer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileSystemOptions;
import org.apache.commons.vfs.RandomAccessContent;
import org.apache.commons.vfs.VFS;
import org.apache.commons.vfs.util.RandomAccessMode;
import org.apache.log4j.Logger;

/**
 * @author pstoellberger
 *
 */
public class VfsFileReader implements ISource, EventProducer, Runnable { 

	private static long INITIAL_WAIT_TIME = 1000;
	private String file;
	private long maxBackoffTime = 30 * 1000;
	private long backOffTime = INITIAL_WAIT_TIME;
	private long messageCount = 0;
	private long blankCount = 0;
	private String streamName;
	private boolean debug = false;

	private Reader reader;
	private FileObject fileObject;
	private boolean tailing = true;


	private LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<String>();
	private Set<io.s4.listener.EventHandler> handlers = new HashSet<io.s4.listener.EventHandler>();

	public VfsFileReader(Properties props) {
		if (props != null) {
			if (props.getProperty("file") != null) {
				file = props.getProperty("file");
			}
			if (props.getProperty("maxBackoffTime") != null) {
				maxBackoffTime = Long.parseLong(props.getProperty("maxBackoffTime"));
			}
			if (props.getProperty("stream") != null) {
				streamName = props.getProperty("stream");
				if (streamName.startsWith("debug")) {
					debug = true;
				}
			}
			if (props.getProperty("tail") != null) {
				tailing = Boolean.parseBoolean(props.getProperty("tail"));
			}
		}
	}

	public void setlogFile(String urlString) {
		this.file = urlString;
	}

	public void setMaxBackoffTime(long maxBackoffTime) {
		this.maxBackoffTime = maxBackoffTime;
	}

	public void setStreamName(String streamName) {
		this.streamName = streamName;
	}

	public void setTailing(boolean tail) {
		this.tailing = tail;
	}

	public boolean isTailing() {
		return tailing;
	}
	
	private boolean isDebug() {
		return debug;
	}

	public long getWaitMillis() {
		long wait = backOffTime;
		backOffTime = backOffTime * 2;
		if (backOffTime > maxBackoffTime) {
			backOffTime = maxBackoffTime;
		}
		return wait;
	}


	public void init() {
		//	        for (int i = 0; i < 1; i++) {
		Dequeuer dequeuer = new Dequeuer(1);
		Thread t = new Thread(dequeuer);
		t.start();
		//	        }
		(new Thread(this)).start();
	}

//	public void runa() {
//		long backoffTime = 1000;
//		while(!Thread.interrupted()) {
//			try {
//				openAndRead();
//			} catch (Exception e) {
//				Logger.getLogger("s4").error("Exception reading logfile", e);
//				try {
//					Thread.sleep(backoffTime);
//					System.out.println("Sleeping for :" + backoffTime/1000 + " seconds");
//				} catch (InterruptedException ie) {
//					Thread.currentThread().interrupt();
//				}
//
//			}
//		}
//	}

//	public void openAndRead() throws Exception {
//		URL url = new URL(file);
//		System.out.println("## Reading File: " + file);
//		URLConnection connection = url.openConnection();
//		InputStream is = connection.getInputStream();
//		InputStreamReader isr = new InputStreamReader(is);
//		BufferedReader br = new BufferedReader(isr);
//
//		String inputLine = null;
//		while ((inputLine = br.readLine()) != null) {
//			if (inputLine.trim().length() == 0) {
//				blankCount++;
//				continue;
//			}
//			messageCount++;
//			messageQueue.add(inputLine);
//		}
//		System.out.println(messageCount + " lines read ");
//		throw new Exception("nothing to read anymore");
//	}

	private void process(BufferedReader br) throws IOException {
		String inputLine = null;
		while ((inputLine = br.readLine()) != null) {
			if (inputLine.trim().length() == 0) {
				blankCount++;
				continue;
			}
			messageCount++;
			messageQueue.add(inputLine);
		}
	}
	public void run() {
		do {
			long lastFilePointer = 0;
			long lastFileSize = 0;
			try {
				do {
					FileSystemManager fileSystemManager = VFS.getManager();

					//fileobject was created above, release it and construct a new one
					if (fileObject != null) {
						fileObject.close();
						fileObject = null;
					}
					fileObject = fileSystemManager.resolveFile(file);
					if (fileObject == null) {
						throw new IOException("File does not exist: " + file);
					}
					//file may not exist..
					boolean fileLarger = false;
					if (fileObject != null && fileObject.exists()) {
						try {
							fileObject.refresh();
						} catch (Error err) {
							System.err.println(file + " - unable to refresh fileobject\n");
							err.printStackTrace();
						}
						//could have been truncated or appended to (don't do anything if same size)
						if (fileObject.getContent().getSize() < lastFileSize) {
							reader = new InputStreamReader(fileObject.getContent().getInputStream());
							System.out.println(file + " was truncated");
							lastFileSize = 0;
							lastFilePointer = 0;
							backOffTime = INITIAL_WAIT_TIME;
						} else if (fileObject.getContent().getSize() > lastFileSize) {
							fileLarger = true;
							RandomAccessContent rac = fileObject.getContent().getRandomAccessContent(RandomAccessMode.READ);
							rac.seek(lastFilePointer);
							reader = new InputStreamReader(rac.getInputStream());
							BufferedReader bufferedReader = new BufferedReader(reader);
							process(bufferedReader);
							lastFilePointer = rac.getFilePointer();
							lastFileSize = fileObject.getContent().getSize();
							rac.close();
						}
						try {
							//release file so it can be externally deleted/renamed if necessary
							fileObject.close();
							fileObject = null;
						}
						catch (IOException e)
						{
							System.err.println(file + " - unable to close fileobject\n");
							e.printStackTrace();
						}
						try {
							if (reader != null) {
								reader.close();
								reader = null;
							}
						} catch (IOException ioe) {
							System.err.println(file + " - unable to close reader\n");
							ioe.printStackTrace();
						}
					} else {
						System.out.println(file + " - not available - will re-attempt to load after waiting " + getWaitMillis() + " millis");
					}

					try {
						synchronized (this) {
							wait(getWaitMillis());
						}
					} catch (InterruptedException ie) {}
					if (isTailing() && fileLarger) {
						System.out.println(file + " - tailing file - file size: " + lastFileSize);
					}
				} while (isTailing() && !Thread.interrupted());
			} catch (IOException ioe) {
				System.err.println(file + " - exception processing file\n");
				ioe.printStackTrace();
				try {
					if (fileObject != null) {
						fileObject.close();
					}
				} catch (FileSystemException e) {
					System.err.println(file + " - exception processing file\n");
					e.printStackTrace();
				}
				try {
					synchronized(this) {
						wait(getWaitMillis());
					}
				} catch (InterruptedException ie) {}
			}
		}while(!Thread.interrupted());

		System.out.println(file + " - processing complete");
	}


	class Dequeuer implements Runnable {
		private int id;

		public Dequeuer(int id) {
			this.id = id;
		}

		public void run() {
			while (!Thread.interrupted()) {
				try {
					String message = messageQueue.take();
					StreamRow sr = new StreamRow();
					sr.set("line", message, ValueType.STRING);
					if (isDebug()) {
						System.out.println(sr);
					}

					EventWrapper ew = new EventWrapper(streamName, sr, null);
					for (io.s4.listener.EventHandler handler : handlers) {
						try {
							handler.processEvent(ew);
						} catch (Exception e) {
							Logger.getLogger("s4")
							.error("Exception in raw event handler", e);
						}
					}
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					Logger.getLogger("s4")
					.error("Exception processing message", e);
				}
			}
		}

	}

	@Override
	public void addHandler(EventHandler handler) {
		handlers.add(handler);

	}

	@Override
	public boolean removeHandler(EventHandler handler) {
		return handlers.remove(handler);
	}

}