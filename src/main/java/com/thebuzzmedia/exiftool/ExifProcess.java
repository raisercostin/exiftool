package com.thebuzzmedia.exiftool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

// ================================================================================
/**
 * Represents an Exif Process.
 */
public class ExifProcess {
	private final ReentrantLock closeLock = new ReentrantLock(false);
	private final boolean keepAlive;
	private final Process process;
	private final BufferedReader reader;
	private final OutputStreamWriter writer;
	private volatile boolean closed = false;

	public static VersionNumber readVersion(String exifCmd) {
		ExifProcess process = _execute(false,
				Arrays.asList(exifCmd, "-ver"));
		try {
			return new VersionNumber(process.readLine());
		} catch (IOException ex) {
			throw new RuntimeException(String.format(
					"Unable to check version number of ExifTool: %s",
					exifCmd));
		} finally {
			process.close();
		}
	}

	public static Map<String, String> executeToResults(String exifCmd,
			List<String> args) throws IOException {
		List<String> newArgs = new ArrayList<String>(args.size() + 1);
		newArgs.add(exifCmd);
		newArgs.addAll(args);
		ExifProcess process = _execute(false, newArgs);
		try {
			return process.readResponse();
		} finally {
			process.close();
		}
	}

	public static String executeToString(String exifCmd, List<String> args)
			throws IOException {
		List<String> newArgs = new ArrayList<String>(args.size() + 1);
		newArgs.add(exifCmd);
		newArgs.addAll(args);
		ExifProcess process = _execute(false, newArgs);
		try {
			return process.readResponseString();
		} finally {
			process.close();
		}
	}

	public static ExifProcess startup(String exifCmd) {
		List<String> args = Arrays.asList(exifCmd, "-stay_open", "True",
				"-@", "-");
		return _execute(true, args);
	}

	public static ExifProcess _execute(boolean keepAlive, List<String> args) {
		ExifTool.log.debug(String
				.format("Attempting to start external ExifTool process using args: %s",
						args));
		try {
			Process process = new ProcessBuilder(args).start();
			ExifTool.log.debug("\tSuccessful");
			return new ExifProcess(keepAlive, process);
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

	public ExifProcess(boolean keepAlive, Process process) {
		this.keepAlive = keepAlive;
		this.process = process;
		this.reader = new BufferedReader(new InputStreamReader(
				process.getInputStream()));
		this.writer = new OutputStreamWriter(process.getOutputStream());
	}

	public synchronized Map<String, String> sendArgs(List<String> args)
			throws IOException {
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

	public synchronized Map<String, String> readResponse()
			throws IOException {
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
				ExifTool.log.debug(String.format("\tRead Tag [name=%s, value=%s]",
						pair[0], pair[1]));
			}

			/*
			 * When using a persistent ExifTool process, it terminates its
			 * output to us with a "{ready}" clause on a new line, we need
			 * to look for it and break from this loop when we see it
			 * otherwise this process will hang indefinitely blocking on the
			 * input stream with no data to read.
			 */
			if (keepAlive && line.equals("{ready}")) {
				break;
			}
		}
		return resultMap;
	}

	public synchronized String readResponseString() throws IOException {
		if (closed)
			throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);
		ExifTool.log.debug("Reading response back from ExifTool...");
		String line;
		StringBuilder result = new StringBuilder();
		while ((line = reader.readLine()) != null) {
			if (closed)
				throw new IOException(ExifTool.STREAM_CLOSED_MESSAGE);

			/*
			 * When using a persistent ExifTool process, it terminates its
			 * output to us with a "{ready}" clause on a new line, we need
			 * to look for it and break from this loop when we see it
			 * otherwise this process will hang indefinitely blocking on the
			 * input stream with no data to read.
			 */
			if (keepAlive && line.equals("{ready}")) {
				break;
			} else
				result.append(line);
		}
		return result.toString();
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
					ExifTool.log.debug("Attempting to close ExifTool daemon process, issuing '-stay_open\\nFalse\\n' command...");
					try {
						writer.write("-stay_open\nFalse\n");
						writer.flush();
					} catch (IOException ex) {
						// log.error(ex,ex);
					}
					try {
						ExifTool.log.debug("Closing Read stream...");
						reader.close();
						ExifTool.log.debug("\tSuccessful");
					} catch (Exception e) {
						// no-op, just try to close it.
					}

					try {
						ExifTool.log.debug("Closing Write stream...");
						writer.close();
						ExifTool.log.debug("\tSuccessful");
					} catch (Exception e) {
						// no-op, just try to close it.
					}

					ExifTool.log.debug("Read/Write streams successfully closed.");

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