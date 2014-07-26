package com.thebuzzmedia.exiftool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

// ================================================================================
/**
 * Represents an external exif process. Works for both single use and keep alive
 * modes. This is the actual process, with streams for reading and writing data.
 */
public final class ExifProcess {
	public static VersionNumber readVersion(String exifCmd) {
		ExifProcess process = new ExifProcess(false, Arrays.asList(exifCmd,
				"-ver"));
		try {
			return new VersionNumber(process.readLine());
		} catch (IOException ex) {
			throw new RuntimeException(String.format(
					"Unable to check version number of ExifTool: %s", exifCmd));
		} finally {
			process.close();
		}
	}

	private final ReentrantLock closeLock = new ReentrantLock(false);
	private final boolean keepAlive;
	private final Process process;
	private final BufferedReader reader;
	private final OutputStreamWriter writer;
	private volatile boolean closed = false;

	public ExifProcess(boolean keepAlive, List<String> args) {
		this.keepAlive = keepAlive;
		ExifTool.log.debug(String.format(
				"Attempting to start ExifTool process using args: %s", args));
		try {
			this.process = new ProcessBuilder(args).start();
			this.reader = new BufferedReader(new InputStreamReader(
					process.getInputStream()));
			this.writer = new OutputStreamWriter(process.getOutputStream());
			ExifTool.log.debug("\tSuccessful");
		} catch (Exception e) {
			String message = "Unable to start external ExifTool process using the execution arguments: "
					+ args
					+ ". Ensure ExifTool is installed correctly and runs using the command path '"
					+ args.get(0)
					+ "' as specified by the 'exiftool.path' system property.";

			ExifTool.log.debug(message);
			throw new RuntimeException(message, e);
		}
	}

	public synchronized Map<String, String> sendToRunning(List<String> args)
			throws IOException {
		if (!keepAlive) {
			throw new IOException("Not KeepAlive Process");
		}
		StringBuilder builder = new StringBuilder();
		for (String arg : args) {
			builder.append(arg).append("\n");
		}
		builder.append("-execute\n");
		writeFlush(builder.toString());
		return readResponse();
	}

	public synchronized void writeFlush(String message) throws IOException {
		if (closed)
			throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);
		writer.write(message);
		writer.flush();
	}

	public synchronized String readLine() throws IOException {
		if (closed)
			throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);
		return reader.readLine();
	}

	public synchronized Map<String, String> readResponse() throws IOException {
		if (closed)
			throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);
		ExifTool.log.debug("Reading response back from ExifTool...");
		Map<String, String> resultMap = new HashMap<String, String>(500);
		String line;

		while ((line = reader.readLine()) != null) {
			if (closed)
				throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);
			String[] pair = ExifTool.TAG_VALUE_PATTERN.split(line, 2);

			if (pair.length == 2) {
				resultMap.put(pair[0], pair[1]);
				ExifTool.log.debug(String.format(
						"\tRead Tag [name=%s, value=%s]", pair[0], pair[1]));
			}

			/*
			 * When using a persistent ExifTool process, it terminates its
			 * output to us with a "{ready}" clause on a new line, we need to
			 * look for it and break from this loop when we see it otherwise
			 * this process will hang indefinitely blocking on the input stream
			 * with no data to read.
			 */
			if (keepAlive && line.equals("{ready}")) {
				break;
			}
		}
		return resultMap;
	}

	public boolean isClosed() {
		return closed;
	}

	public void close() {
		if (!closed) {
			closeLock.lock();
			try {
				if (!closed) {
					closed = true;
					try {
						ExifTool.log.debug("Closing Read stream...");
						reader.close();
						ExifTool.log.debug("\tSuccessful");
					} catch (Exception e) {
						// no-op, just try to close it.
					}

					try {
						ExifTool.log
								.debug("Attempting to close ExifTool daemon process, issuing '-stay_open\\nFalse\\n' command...");
						writer.write("-stay_open\nFalse\n");
						writer.flush();
					} catch (IOException ex) {
						// log.error(ex,ex);
					}

					try {
						ExifTool.log.debug("Closing Write stream...");
						writer.close();
						ExifTool.log.debug("\tSuccessful");
					} catch (Exception e) {
						// no-op, just try to close it.
					}

					ExifTool.log
							.debug("Read/Write streams successfully closed.");

					try {
						process.destroy();
					} catch (Exception e) {
						//
					}
					// process = null;

				}
			} finally {
				closeLock.unlock();
			}
		}
	}
}