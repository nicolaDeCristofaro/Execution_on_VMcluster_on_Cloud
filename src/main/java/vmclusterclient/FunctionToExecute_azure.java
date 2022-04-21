package vmclusterclient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONObject;

import azureclient.AzureClient;

public class FunctionToExecute_azure {
	
	static long  __id_execution;
	static AzureClient azure = null;
	static ExecutorService __thread_pool_vm_cluster = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	
	//fixed vector of 100 integer for example
	final static Integer [] vector = {7,8,1,3,1,5,9,4,3,4,7,6,3,1,8,9,7,3,5,0,1,3,8,8,8,8,4,7,8,1,3,6,5,2,1,5,4,1,9,1,8,5,9,0,5,9,3,1,2,3,8,7,5,4,2,5,1,6,4,4,8,7,2,5,1,4,0,2,7,5,2,7,6,3,7,0,2,5,6,4,6,8,0,2,6,0,2,3,8,6,5,2,1,3,3,6,4,2,8,4};
	
	public static void main(String[] args) throws Exception{
		int __numThreadsAvailable = Runtime.getRuntime().availableProcessors();
		
		String __myObjectInputFileName = args[0];
		__id_execution = Long.parseLong(args[1]);
		
		azure = new AzureClient("***",  //insert here your clientID
				"***", //insert here your tenantID
				"***", //insert here your secretKey
				"***", //insert here your subscriptionID
				__id_execution+"",
				"West Europe",
				"no-termination-queue");
				
		azure.VMClusterInit();
		
		azure.createQueue("results-queue-"+__id_execution);
		
		final List<Future<Object>> matrixVectorMultiplication_0_return = new ArrayList<Future<Object>>();
		List<String> lines = Collections.emptyList();
		lines = Files.readAllLines(Paths.get(__myObjectInputFileName), StandardCharsets.UTF_8);
		
		int __mySplitsCount = Integer.parseInt(lines.get(0));
		lines.remove(0);
		
		int __numThreadsToUse = __numThreadsAvailable;
		if (__mySplitsCount < __numThreadsAvailable) __numThreadsToUse = __mySplitsCount;
		
		for(int _i=0;_i< __numThreadsToUse;_i++){
			final int __i = _i;
			final String i_Split = lines.get(__i);
			Future<Object> _f = __thread_pool_vm_cluster.submit(new Callable<Object>(){
						
				public Object call() throws Exception {
					
					Object __ret = matrixVectorMultiplication(i_Split);
					return __ret;
				}
			});
			matrixVectorMultiplication_0_return.add(_f);
		}
		for(Future _f : matrixVectorMultiplication_0_return){
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
		
		azure.addToQueue("results-queue-"+__id_execution,String.valueOf((new JSONObject("{\"portionValues\":"+ Arrays.deepToString(vectorPortionResult)+
				" , \"portionLength\":"+vectorPortionResult.length+ 
				",  \"portionIndex\":"+__portionIndex+
				" , \"portionDisplacement\":"+__portionDisplacement+"}").toString())));
		return null;
	}

}
