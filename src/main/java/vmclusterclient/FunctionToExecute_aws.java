package vmclusterclient;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import awsclient.AWSClient;

public class FunctionToExecute_aws {
	
	static long  __id_execution;
	static AWSClient aws = null;
	static BasicAWSCredentials creds = new BasicAWSCredentials("", ""); //insert here your AWS credentials
	static ExecutorService __thread_pool_vm_cluster = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	
	static AmazonSQS __sqs_aws = AmazonSQSClientBuilder.standard()
			.withRegion("eu-west-2")							 
			.withCredentials(new AWSStaticCredentialsProvider(creds))
			.build();
	
	//fixed vector of 100 integer for example
	final static Integer [] vector = {7,8,1,3,1,5,9,4,3,4,7,6,3,1,8,9,7,3,5,0,1,3,8,8,8,8,4,7,8,1,3,6,5,2,1,5,4,1,9,1,8,5,9,0,5,9,3,1,2,3,8,7,5,4,2,5,1,6,4,4,8,7,2,5,1,4,0,2,7,5,2,7,6,3,7,0,2,5,6,4,6,8,0,2,6,0,2,3,8,6,5,2,1,3,3,6,4,2,8,4};
	
	public static void main(String[] args) throws Exception{
		int __numThreadsAvailable = Runtime.getRuntime().availableProcessors();
		
		String __myObjectInputFileName = args[0];
		__id_execution = Long.parseLong(args[1]);
		
		aws = new AWSClient(creds,"eu-west-2");
		aws.setupS3Bucket("bucketvmclusterexec");
		
		//Download the file of the portion to compute from S3 bucket
		aws.downloadS3ObjectToFile(__myObjectInputFileName);
		
		final List<Future<Object>> matrixVectorMultiplication_7_return = new ArrayList<Future<Object>>();
		
		//Read the file 
		FileInputStream __fis = new FileInputStream(__myObjectInputFileName);       
		Scanner __sc = new Scanner(__fis);
		
		ArrayList<String> __mySplits = new ArrayList<>();
		int __mySplitsCount = 0;
		int __y = 0;
		while(__sc.hasNextLine()){
			String __c = __sc.nextLine(); 
			if (__y++ == 0) __mySplitsCount = Integer.parseInt(__c); //take the number of splits
			else __mySplits.add(__c); //take the actual split
		}
		__sc.close();
				
		int __numThreadsToUse = __numThreadsAvailable;
		if (__mySplitsCount < __numThreadsAvailable) __numThreadsToUse = __mySplitsCount;
		
		for(int _i=0;_i< __numThreadsToUse;_i++){
			final int __i = _i;
			Future<Object> _f = __thread_pool_vm_cluster.submit(new Callable<Object>(){
						
				public Object call() throws Exception {
					
					Object __ret = matrixVectorMultiplication(__mySplits.get(__i)); //here there is the function to execute (in this case matrix vect multiplication)
					return __ret;
				}
			});
			matrixVectorMultiplication_7_return.add(_f);
		}
		for(Future<Object> _f : matrixVectorMultiplication_7_return){
			try{
				_f.get();
			} catch(Exception e){
				e.printStackTrace();
			}
		}
		
		__thread_pool_vm_cluster.shutdown();
		System.exit(0);
	}
	
	protected static  Object matrixVectorMultiplication(String __myMatrixPortion)throws Exception{
		
		//Read the JSON string and extract useful fields
		JSONObject __jsonObject = new JSONObject(__myMatrixPortion);
		__jsonObject = new JSONObject(__jsonObject.getJSONArray("data").get(0).toString());
		
		Integer __portionRows = __jsonObject.getInt("portionRows");
		Integer __portionCols = __jsonObject.getInt("portionCols");
		Integer __portionIndex = __jsonObject.getInt("portionIndex");
		Integer __portionDisplacement = __jsonObject.getInt("portionDisplacement");
		
		Integer[][] myMatrixPortion = new Integer[__portionRows][__portionCols];
		
		//Re-construct my matrix portion
		JSONArray __portionValues = __jsonObject.getJSONArray("portionValues");
		
		int __indexValues = 0;	
		for (int i = 0; i < __portionRows; i++) {
			for(int j = 0; j < __portionCols; j++){
				myMatrixPortion[i][j] = new JSONObject(__portionValues.get(__indexValues++).toString()).getInt("value");
			}
		}
		Integer myRows = myMatrixPortion.length;
		
		Integer myCols = myMatrixPortion[0].length;
		
		Integer myDisplacement = __portionDisplacement;
		
		Integer[] vectorPortionResult = new Integer[myRows + 1];
		
		
		for(int r=0;r<myRows;r++){
			Integer sum = 0;
			for(int c=0;c<myCols;c++){
					sum += myMatrixPortion[r][c] * vector[c];
			}
			vectorPortionResult[r] = sum;
		}
		
		vectorPortionResult[myRows] = myDisplacement;
		
		__sqs_aws.sendMessage(new SendMessageRequest(__sqs_aws.getQueueUrl("results-queue-"+__id_execution).getQueueUrl(), String.valueOf((new JSONObject("{\"portionValues\":"+ Arrays.deepToString(vectorPortionResult)+
														" , \"portionLength\":"+vectorPortionResult.length+ 
														",  \"portionIndex\":"+__portionIndex+
														" , \"portionDisplacement\":"+__portionDisplacement+"}").toString()))));;
		return null;
	}

}
