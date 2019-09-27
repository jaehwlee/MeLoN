package kr.ac.mju.idpl.melon;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.ac.mju.idpl.melon.rpc.RPCProtocol;
import kr.ac.mju.idpl.melon.util.Utils;

public class MeLoN_TaskExecutor {
	private static final Logger LOG = LoggerFactory.getLogger(MeLoN_TaskExecutor.class);
	private Configuration melonConf = new Configuration(false);
	private String jobName;
	private int taskIndex;
	private int numTasks;
	private String taskId;
	private String amHost;
	private int amPort;
	private String clusterSpec;
	private Map<String, String> shellEnvs = new HashMap<>();
	private String taskCommand;
	private RPCProtocol amClient;
	private ServerSocket rpcSocket;
	private int rpcPort;
	private Configuration yarnConf = new Configuration(false);
	private Configuration hdfsConf = new Configuration(false);
	private int exitCode = -1;

	public static void main(String[] args) {
		LOG.info("MeLoN_TaskExecutor is running...");
		MeLoN_TaskExecutor executor = new MeLoN_TaskExecutor();
		int exitCode = -1;
		try {
			exitCode = executor.run();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		LOG.info("Child process exited with exit code " + exitCode);
		System.exit(exitCode);
	}

	private int run() throws Exception {
		initConfigs();
		Utils.extractResources();

		LOG.info("This container's jobName is {}", jobName);
		InetSocketAddress addr = new InetSocketAddress(amHost, amPort);
		try {
			amClient = RPC.getProxy(RPCProtocol.class, RPCProtocol.versionID, addr, yarnConf);
		} catch (IOException e) {
			LOG.error("Connecting to ApplicationMaster " + amHost + ":" + amPort + " failed!");
			LOG.error("Container will suicide!");
			System.exit(1);
		}
		rpcSocket = new ServerSocket(0);
		rpcPort = rpcSocket.getLocalPort();
		LOG.info("Reserved rpcPort: " + this.rpcPort);
		clusterSpec = registerAndGetClusterSpec();
		if (clusterSpec == null) {
			LOG.error("Failed to register worker with AM.");
			throw new Exception("Failed to register worker with AM.");
		}
		LOG.info("Successfully registered and got cluster spec: {}", clusterSpec);

		shellEnvs.put(MeLoN_Constants.JOB_NAME, String.valueOf(jobName));
		shellEnvs.put(MeLoN_Constants.TASK_INDEX, String.valueOf(taskIndex));
		shellEnvs.put(MeLoN_Constants.CLUSTER_SPEC, String.valueOf(clusterSpec));

		releasePorts();

		exitCode = executeShell();
		LOG.info("Execute shell is finished with exitcode {}", exitCode);
		registerExecutionResult();
		return exitCode;
	}

	private void registerExecutionResult() throws Exception {
		String response;
		int attempt = 60;
		while (attempt > 0) {
			response = amClient.registerExecutionResult(exitCode, jobName, String.valueOf(taskIndex));
			if (response != null) {
				LOG.info("AM response for result execution run: " + response);
				break;
			}
			Thread.sleep(1000);
			attempt--;
		}
	}

	private int executeShell() throws IOException, InterruptedException {
		LOG.info("Executing command: " + taskCommand);
		String executablePath = taskCommand.trim().split(" ")[0];
		File executable = new File(executablePath);

		if (!executable.canExecute()) {
			if (!executable.setExecutable(true)) {
				LOG.warn("Failed to make " + executable + " executable");
			}
		}
		ProcessBuilder taskProcessBuilder = new ProcessBuilder("bash", "-c", taskCommand);
		taskProcessBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
		taskProcessBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

		taskProcessBuilder.environment().remove("MALLOC_ARENA_MAX");
		if (shellEnvs != null) {
			taskProcessBuilder.environment().putAll(shellEnvs);
		}
		Process taskProcess = taskProcessBuilder.start();
		taskProcess.waitFor();
		return taskProcess.exitValue();
	}

	private void initConfigs() throws Exception {
		jobName = System.getenv(MeLoN_Constants.JOB_NAME);
		taskIndex = Integer.parseInt(System.getenv(MeLoN_Constants.TASK_INDEX));
		numTasks = Integer.parseInt(System.getenv(MeLoN_Constants.TASK_NUM));
		taskId = jobName + ":" + taskIndex;
		LOG.info("Executor is running task " + taskId);

		amHost = System.getenv(MeLoN_Constants.AM_HOST);
		amPort = Integer.parseInt(System.getenv(MeLoN_Constants.AM_PORT));

		melonConf.addResource(new Path(MeLoN_Constants.MELON_FINAL_XML));
		String[] shellEnvsStr = melonConf.getStrings(MeLoN_ConfigurationKeys.SHELL_ENVS);
		shellEnvs = Utils.parseKeyValue(shellEnvsStr);
		taskCommand = melonConf.get(MeLoN_ConfigurationKeys.getTaskCommandKey(jobName),
				melonConf.get(MeLoN_ConfigurationKeys.CONTAINERS_COMMAND));
		if (taskCommand == null) {
			LOG.error("Task command is empty. Please see task command in configuration files.");
			throw new IllegalArgumentException();
		}
		LOG.info("Task command: " + taskCommand);

		Utils.initYarnConf(yarnConf);
		Utils.initHdfsConf(hdfsConf);
	}

	private void releasePorts() throws IOException {
		if (this.rpcSocket != null) {
			this.rpcSocket.close();
		}
	}

	private String registerAndGetClusterSpec() throws IOException, YarnException, InterruptedException {
		String receivedClusterSpec = null;
		ContainerId containerId = ContainerId
				.fromString(System.getenv(ApplicationConstants.Environment.CONTAINER_ID.name()));
		String hostname = System.getenv(ApplicationConstants.Environment.NM_HOST.name());
		LOG.info("Connecting to " + amHost + ":" + amPort + " to register worker spec: " + jobName + " " + taskIndex
				+ " " + hostname + ":" + rpcPort);
		while (true) {
			receivedClusterSpec = amClient.registerWorkerSpec(jobName + ":" + taskIndex, hostname + ":" + rpcPort);
			if (receivedClusterSpec != null) {
				LOG.info("Received clusterSpec: " + receivedClusterSpec);
				return receivedClusterSpec;
			}
			Thread.sleep(3000);
		}
	}
}
