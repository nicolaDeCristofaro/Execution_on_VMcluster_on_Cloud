package vmclusterclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;

import org.json.JSONObject;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;

import awsclient.AWSClient;

public class VMClusterExecution {
	
	static long  __id_execution =  System.currentTimeMillis();
	static boolean __wait_on_termination_queue = false;
	static boolean __wait_on_results_queue = false;
	
	static Integer M = 100;	
	static Integer N = 100;
	static Integer vmCount = 2;
	
	static LinkedTransferQueue<String> __results__local_queue  = new LinkedTransferQueue<String>();

	public static void main(String[] args) throws Exception{
		
		//args -> [0]cloud, [1]vmTypeSize, [2]on-demand OR spot, [3]vmCount, [4]persistent, [5]region
		//default
		String cloud = "aws";
		String vmType = "t2.micro";
		String purchasingOption = "on-demand";
		int vmCount = 2;
		boolean persistent = true;
		String region = "eu-west-2";

		if (args.length == 6) {
			cloud = args[0];
			vmType = args[1];
			purchasingOption = args[2];
			vmCount = Integer.parseInt(args[3]);
			persistent = Boolean.parseBoolean(args[4]);
			region = args[5];
		}else if(args.length == 1) {
			cloud = args[0];
		}//else default
		
		int numLocalThreadsUsed = 4;
		ExecutorService __thread_pool_local = Executors.newFixedThreadPool(numLocalThreadsUsed); //4 local threads to gather results from cloud
		
		//Matrix-Vector multiplication example
		
		//initialize Matrix
		Integer[][] matrix = new Integer[M][N];
		Integer min = 0;
		Integer max = 10;
		
		for(int i=0;i<M;i++){
			for(int j=0;j<N;j++){
					Random r = new Random();
					Integer x = r.nextInt(max - min) + min;
					matrix[i][j] =  x;
			}
		}
		
		//cloud support: AWS & Azure...extensible to other cloud providers
		switch(cloud) {
			case "aws":
				
				BasicAWSCredentials creds = new BasicAWSCredentials("", ""); //insert here your AWS credentials
				
				//create SQS client to handle messages on queue on AWS
				AmazonSQS __sqs_aws = AmazonSQSClientBuilder.standard()
						.withRegion("eu-west-2")							 
						.withCredentials(new AWSStaticCredentialsProvider(creds))
						.build();
				
				//Create onCloud queue and local queue that receives termination messages from onCloud queue
				__wait_on_termination_queue=true;
				__sqs_aws.createQueue(new CreateQueueRequest("termination-queue-"+__id_execution));
				LinkedTransferQueue<String> __termination__local_queue  = new LinkedTransferQueue<String>();
				final String __termination_queue_url = __sqs_aws.getQueueUrl("termination-queue-"+__id_execution).getQueueUrl();
				for(int __i=0;__i< numLocalThreadsUsed;__i++){ 
					__thread_pool_local.submit(new Callable<Object>() {
						@Override
						public Object call() throws Exception {
							while(__wait_on_termination_queue) {
								ReceiveMessageRequest __recmsg = new ReceiveMessageRequest(__termination_queue_url).
										withWaitTimeSeconds(1).withMaxNumberOfMessages(10);
								ReceiveMessageResult __res = __sqs_aws.receiveMessage(__recmsg);
								for(Message msg : __res.getMessages()) { 
									__termination__local_queue.put(msg.getBody());
									__sqs_aws.deleteMessage(__termination_queue_url, msg.getReceiptHandle());
								}
							}
							return null;
						}
					});
				}
				
				//Create onCloud queue and local queue that receives results messages from onCloud queue
				__wait_on_results_queue=true;
				__sqs_aws.createQueue(new CreateQueueRequest("results-queue-"+__id_execution));
				
				for(int __i=0;__i< numLocalThreadsUsed;__i++){ 
					__thread_pool_local.submit(new Callable<Object>() {
						@Override
						public Object call() throws Exception {
							while(__wait_on_results_queue) {
								ReceiveMessageRequest __recmsg = new ReceiveMessageRequest(__sqs_aws.getQueueUrl("results-queue-"+__id_execution).getQueueUrl()).
										withWaitTimeSeconds(1).withMaxNumberOfMessages(10);
								ReceiveMessageResult __res = __sqs_aws.receiveMessage(__recmsg);
								for(Message msg : __res.getMessages()) { 
									__results__local_queue.put(msg.getBody());
									__sqs_aws.deleteMessage(__sqs_aws.getQueueUrl("results-queue-"+__id_execution).getQueueUrl(), msg.getReceiptHandle());
								}
							}
							return null;
						}
					});
				}
				
				AWSClient awsClient = new AWSClient(creds, region);  //Create AWS client object
				awsClient.setupTerminationQueue(__termination_queue_url); //Setup queue where msg of termination are sent
				awsClient.setupS3Bucket("bucketvmclusterexec"); //setup S3 bucket with the given name
				
				//Get vCPUs of the type selected
				int vCPUsCount = awsClient.getVCPUsCount(vmType);
				
				awsClient.zipAndUploadCurrentProject(); //ZIP and upload current project to S3 bucket
				
				//Create the cluster of virtual machines with the given parameters
				int vmsCreatedCount = awsClient.launchVMCluster(vmType, purchasingOption, persistent, vmCount);
				
				//Wait for cluster creation to complete
				if ( vmsCreatedCount != 0) {
					//waiting for boot if it is the first cluster creation
					System.out.print("\n\u27A4 Waiting for virtual machines boot script to complete...");
					while ( __termination__local_queue.size() != vmsCreatedCount);
					System.out.println("Done");
				}
				if(vmsCreatedCount != vmCount){
					//waiting for download project on vm cluster if an existent cluster it is used
					if ( vmsCreatedCount > 0) awsClient.downloadProjectOnVMCluster();
					
					System.out.print("\n\u27A4 Waiting for download project on VM CLuster to complete...");
					while (__termination__local_queue.size() != (vmCount+vmsCreatedCount));
				}
				System.out.println("Done");
				
				//launch the project building on VM cluster instances
				String mainClass = "vmclusterclient.FunctionToExecute_aws";
				awsClient.buildProjectOnVMCluster(mainClass);
				
				//Balanced distribution of job/input matrix to VM cluster instances
				int splitCount = vCPUsCount * vmCount;
				int vmCountToUse = vmCount;
				ArrayList<StringBuilder> __temp_matrix = new ArrayList<StringBuilder>();
				ArrayList<String> portionInputs = new ArrayList<String>();
				
				int __rows = matrix.length;
				int __cols = matrix[0].length;
				
				int __current_row_matrix = 0;
																	
				if ( __rows < splitCount) splitCount = __rows;
				if ( splitCount < vmCountToUse) vmCountToUse = splitCount;
											
				int[] dimPortions = new int[splitCount]; 
				int[] displ = new int[splitCount]; 
				int offset = 0;
											
				for(int __i=0;__i<splitCount;__i++){
					dimPortions[__i] = (__rows / splitCount) + ((__i < (__rows % splitCount)) ? 1 : 0);
					displ[__i] = offset;								
					offset += dimPortions[__i];
												
					__temp_matrix.add(__i,new StringBuilder());
					__temp_matrix.get(__i).append("{\"portionRows\":"+dimPortions[__i]+",\"portionCols\":"+__cols+",\"portionIndex\":"+__i+",\"portionDisplacement\":"+displ[__i]+",\"portionValues\":[");							
						
					for(int __j=__current_row_matrix; __j<__current_row_matrix+dimPortions[__i];__j++){
						for(int __z = 0; __z<matrix[__j].length;__z++){
							__temp_matrix.get(__i).append("{\"x\":"+__j+",\"y\":"+__z+",\"value\":"+matrix[__j][__z]+"},");
						}
						if(__j == __current_row_matrix + dimPortions[__i]-1) {
							__temp_matrix.get(__i).deleteCharAt(__temp_matrix.get(__i).length()-1);
							__temp_matrix.get(__i).append("]}");
						}
					}
					__current_row_matrix +=dimPortions[__i];
					portionInputs.add(__generateString(__temp_matrix.get(__i).toString(),7));
				}
				int numberOfFunctions = splitCount;
				int notUsedVMs = vmCount - vmCountToUse;
				
				System.out.print("\n\u27A4 Waiting for building project on VM CLuster to complete...");
				if(vmsCreatedCount != vmCount){
					while ( __termination__local_queue.size() != ( (vmCount*2)+vmsCreatedCount));
				} else {
					while (__termination__local_queue.size() != (vmCount*2));
				}
				System.out.println("Done");
				
				awsClient.launchExecutionOnVMCluster(portionInputs,
						numberOfFunctions,
						__id_execution);

				System.out.print("\n\u27A4 Waiting for execution to complete...");
				if(vmsCreatedCount != vmCount){
				while (__termination__local_queue.size() != ( (vmCount*3)+vmsCreatedCount-notUsedVMs));
				} else {
				while (__termination__local_queue.size() != (vmCount*3-notUsedVMs ));
				}
				__wait_on_termination_queue=false;
				System.out.println("Done");
				
				//Check for execution errors
				String err_exec_7 = awsClient.checkForExecutionErrors();
				if (err_exec_7 != null) {
					//Print the error within each VM
					System.out.println("The execution failed with the following errors in each VM:");
					System.out.println(err_exec_7);
				}else {
					//No execution errors
					//Manage the callback
					aggregateResults();
				}
				
				//Delete documents with commands
				awsClient.cleanResources();
				
				__wait_on_results_queue=false;
				__sqs_aws.deleteQueue(new DeleteQueueRequest("results-queue-"+__id_execution));
				
				__sqs_aws.deleteQueue(new DeleteQueueRequest("termination-queue-"+__id_execution));
				awsClient.deleteResourcesAllocated();
				
				__thread_pool_local.shutdown();
								
				break;
			case "azure":
				break;
			default:
				System.out.println("Cloud provider "+cloud+" not supported yet");
				System.exit(-1);
		}
		System.exit(0);
	}
	
	protected static  Object aggregateResults()throws Exception{
		Integer[] vectorResult = new Integer[M];
		
		for(int i=0;i<vmCount;i++){
			String __res_ch = __results__local_queue.take().toString();
			__res_ch= __res_ch.replace("&amp;","&")
				    .replace("&lt;", "<")
				    .replace("&gt;", ">")
				    .replace("&quot;", "\"")
				    .replace("&apos;", "\'");
								    
			JSONObject __jsonObject = new JSONObject(__res_ch);
												
			int __arr_length = __jsonObject.getInt("portionLength");
			int __portionIndex = __jsonObject.getInt("portionIndex");
			int __portionDisplacement = __jsonObject.getInt("portionDisplacement");
												
			//extract values from json string
			String __valuesJson = __jsonObject.get("portionValues").toString();
			String __extractedItems = __valuesJson.substring(1,__valuesJson.length()-1).replaceAll("\\s", "");
			String[] __items = __extractedItems.split(",");
			
			
			Integer[] vectorPortionResult = new Integer[__arr_length];
												for (int j=0; j < __arr_length; j++) {
													vectorPortionResult[j] = Integer.parseInt(__items[j]);
												}
			Integer portionLength = vectorPortionResult.length;
			
			Integer myDispl = vectorPortionResult[portionLength - 1];
			
			Integer endIndex = myDispl + (portionLength - 1);
			
			Integer portionIndex = 0;
			
			
			for(int k=myDispl;k<endIndex;k++){
					vectorResult[k] = vectorPortionResult[portionIndex];
					portionIndex++;
			}
		}
		
		System.out.println(("Matrix-Vector multiplication result:"));
		
		System.out.println((Arrays.deepToString(vectorResult)));
		return null;
	}
	
	private static String __generateString(String s,int id) {
		StringBuilder b = new StringBuilder();
		b.append("{\"id\":\""+id+"\",\"data\":");
		b.append("[");
		String[] tmp = s.split("\n");
		for(String t: tmp){
			b.append(t);
			if(t != tmp[tmp.length-1]){
				b.append(",");
			} 
		}
		b.append("]}");
		return b.toString();
	}

}
