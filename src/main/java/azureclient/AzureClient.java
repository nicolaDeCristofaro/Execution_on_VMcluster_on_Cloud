 package azureclient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.management.storage.StorageAccountSkuType;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobContainerPermissions;
import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.queue.CloudQueue;
import com.microsoft.azure.storage.queue.CloudQueueClient;
import com.microsoft.azure.storage.queue.CloudQueueMessage;
import com.microsoft.rest.LogLevel;

public class AzureClient {
	private final static Logger LOGGER = Logger.getLogger("VM Cluster exec on Azure");

	private String clientId;
	private String tenantId;
	private String secret;
	private String subscriptionId;

	private String id;
	private Region region;

	private Azure azure;
	private ResourceGroup resourceGroup;
	private StorageAccount storageAccount;
	private CloudStorageAccount cloudStorageAccount;

	private HashMap<String, CloudQueue> queues;
	
	private VMClusterHandler vmClusterHandler;
	private String projectID;
	private String terminationQueueName;

	private AsyncHttpClient httpClient;
	
	public AzureClient(String clientId, String tenantId, String secret, String subscriptionId, String id, String region, String terminationQueueName)
			throws CloudException, IOException {
		
		DefaultAsyncHttpClientConfig.Builder clientBuilder = Dsl.config().setConnectTimeout(10000);
		httpClient = Dsl.asyncHttpClient(clientBuilder);
		this.clientId = clientId;
		this.tenantId = tenantId;
		this.secret = secret;
		this.subscriptionId = subscriptionId;

		this.id = id;
		this.region = Region.fromName(region);
		this.terminationQueueName = terminationQueueName.toLowerCase();
		queues = new HashMap<>();

		this.azure = login();
		
		//VM Cluster handling
		this.vmClusterHandler = new VMClusterHandler(this.azure, this.region, this.subscriptionId, this.id);
	}
	
	
	private Azure login() throws CloudException, IOException {
		ApplicationTokenCredentials credentials = new ApplicationTokenCredentials(clientId, tenantId, secret,
				AzureEnvironment.AZURE);
		Azure.Authenticated authenticated = Azure.configure().withLogLevel(LogLevel.BODY_AND_HEADERS)
				.authenticate(credentials);
		if (subscriptionId != null) {
			return authenticated.withSubscription(subscriptionId);
		} else {
			Azure azure = authenticated.withDefaultSubscription();
			if (azure.subscriptionId() == null) {
				throw new IllegalArgumentException("There is no default subscription");
			}
			return azure;
		}
	}

	public void init() throws InvalidKeyException, URISyntaxException {
		LOGGER.info("Preparing the necessary for azure client");
		createResourceGroup();
		createStorageAccount();
		LOGGER.info("Init finish");
	}

	private void createResourceGroup() {
		LOGGER.info("Creating resource group...");
		this.resourceGroup = azure.resourceGroups().define("vmclusterexecrg" + id).withRegion(region).create();
		LOGGER.info("Resource group 'vmclusterexecrg" + id + "' created");
	}

	private void createStorageAccount() throws InvalidKeyException, URISyntaxException {
		LOGGER.info("Creating storage account...");
		this.storageAccount = azure.storageAccounts().define("clustersa" + id).withRegion(region)
				.withExistingResourceGroup(this.resourceGroup).withSku(StorageAccountSkuType.STANDARD_LRS).create();

		String storageConnectionString = "DefaultEndpointsProtocol=https;" + "AccountName=" + storageAccount.name()
				+ ";" + "AccountKey=" + storageAccount.getKeys().get(0).value() + ";"
				+ "EndpointSuffix=core.windows.net";
		// Connect to azure storage account
		cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
		LOGGER.info("Storage account 'clustersa" + id + "' created");
	}

	public String uploadFile(java.io.File file)
			throws URISyntaxException, StorageException, InvalidKeyException, IOException {
		CloudBlobClient blobClient = cloudStorageAccount.createCloudBlobClient();
		CloudBlobContainer container = blobClient.getContainerReference("bucket-" + id);
		container.createIfNotExists();

		BlobContainerPermissions containerPermissions = new BlobContainerPermissions();
		containerPermissions.setPublicAccess(BlobContainerPublicAccessType.CONTAINER);
		container.uploadPermissions(containerPermissions);

		CloudBlockBlob blob = container.getBlockBlobReference(file.getName());
		blob.upload(new FileInputStream(file), file.length());
		return blob.getUri().toString();
	}
	
	public String downloadFile(String fileName)
			throws URISyntaxException, StorageException, InvalidKeyException, IOException {
		CloudBlobClient blobClient = cloudStorageAccount.createCloudBlobClient();
		CloudBlobContainer container = blobClient.getContainerReference("bucket-" + id);

		CloudBlockBlob blob = container.getBlockBlobReference(fileName);
		blob.downloadToFile(fileName);
		return blob.getUri().toString();
	}

	public void createQueue(String name) throws Exception {
		name = name.toLowerCase();
		CloudQueueClient queueClient = cloudStorageAccount.createCloudQueueClient();
		CloudQueue queue = queueClient.getQueueReference(name);
		
		queue.setShouldEncodeMessage(false);
		queue.create();
		queues.put(name, queue);
	}

	public String peekFromQueue(String name) throws Exception {
		name = name.toLowerCase();
		CloudQueueMessage message = queues.get(name).retrieveMessage();
		String msg = message.getMessageContentAsString();
		queues.get(name).deleteMessage(message);
		System.out.println("get message from " + name);
		return msg;
	}

	public List<String> peeksFromQueue(String name, int n) throws Exception {
		name = name.toLowerCase();
		List<String> values = new ArrayList<>();
		for (CloudQueueMessage message : queues.get(name).retrieveMessages(n)) {
			values.add(message.getMessageContentAsString());
			queues.get(name).deleteMessage(message);
		}
		return values;
	}

	public void addToQueue(String name, String value) throws StorageException {
		name = name.toLowerCase();
		CloudQueueMessage sentMessage = new CloudQueueMessage(value);
		queues.get(name).addMessage(sentMessage);
	}
	
	public long getQueueLength(String name) throws Exception {
		CloudQueueClient queueClient = cloudStorageAccount.createCloudQueueClient();
		CloudQueue queue = queueClient.getQueueReference(name);
		
		queue.downloadAttributes();
		
		return queue.getApproximateMessageCount();
	}

	private String getOAuthToken() throws IOException {
		URL url = new URL("https://login.microsoftonline.com/" + tenantId + "/oauth2/token");
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		// Set connections properties
		connection.setDoOutput(true);
		connection.setDoInput(true);
		connection.setRequestMethod("POST");

		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("grant_type", "client_credentials"));
		params.add(new BasicNameValuePair("client_id", clientId));
		params.add(new BasicNameValuePair("client_secret", secret));
		params.add(new BasicNameValuePair("resource", "https://management.azure.com/"));

		OutputStream os = connection.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
		writer.write(getQuery(params));
		writer.flush();
		writer.close();
		os.close();

		Gson gson = new GsonBuilder().create();
		JsonReader jsonReader = gson.newJsonReader(new InputStreamReader(connection.getInputStream()));
		OAuthReply oAuthReply = gson.fromJson(jsonReader, OAuthReply.class);
		return oAuthReply.access_token;
	}

	private class OAuthReply {
		String access_token;
	}

	/**
	 * Builds query params for http request.
	 *
	 * @param params
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	private String getQuery(List<NameValuePair> params) throws UnsupportedEncodingException {
		StringBuilder result = new StringBuilder();
		boolean first = true;
		for (NameValuePair pair : params) {
			if (first)
				first = false;
			else
				result.append("&");
			result.append(URLEncoder.encode(pair.getName(), "UTF-8"));
			result.append("=");
			result.append(URLEncoder.encode(pair.getValue(), "UTF-8"));
		}
		return result.toString();
	}

	//VM Cluster handling methods
	public void VMClusterInit() throws InvalidKeyException, URISyntaxException {
		
		boolean resourceGroupFound = false;
		for (ResourceGroup rg : azure.resourceGroups().list()) {
			if (rg.name().contains("vmclusterexecrg")) {
				this.resourceGroup = rg;
				resourceGroupFound = true;
			}
		}
		if(!resourceGroupFound) createResourceGroup();
		
		boolean storageAccountFound = false;
		for (StorageAccount sa : azure.storageAccounts().list()) {
			if (sa.name().contains("clustersa")) {
				this.storageAccount = sa;
				
				String storageConnectionString = "DefaultEndpointsProtocol=https;" + "AccountName=" + this.storageAccount.name()
				+ ";" + "AccountKey=" + this.storageAccount.getKeys().get(0).value() + ";"
				+ "EndpointSuffix=core.windows.net";
		
				// Connect to azure storage account
				this.cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);

				storageAccountFound = true;
			}
		}
		if(!storageAccountFound) createStorageAccount();
	}
	
	//Upload ZIP of current project to the storage account
	public void zipAndUploadCurrentProject() 
			throws InvalidKeyException, URISyntaxException, StorageException, IOException, InterruptedException {
		
		String whereami = "";
		System.out.println("\n\u27A4 ZIP generation and upload current project to a container on Azure.");
			
		//Generate ZIP
		System.out.print("   \u2022 ZIP generation...");
		
		//Get the project folder name
		Process p = Runtime.getRuntime().exec("pwd");
		BufferedReader p_output2 = new BufferedReader(new InputStreamReader(p.getInputStream()));
		whereami = p_output2.readLine();
		whereami = whereami.substring(whereami.lastIndexOf(File.separator) + 1);
		
		//ZIP generation
		p = Runtime.getRuntime().exec("zip -r "+whereami+".zip ../"+whereami);
		p.waitFor();
		
	    System.out.println("Done");
				
	    //Get the file ZIP to upload
		File f = new File(whereami+".zip");
		
		System.out.print("   \u2022 Uploading ZIP file to Azure...");
		String uriBlob = uploadFile(f);
		Files.delete(Path.of(whereami+".zip"));
		System.out.println("Done");
		
		this.projectID = uriBlob;
	}
	 
	 public int launchVMCluster(String vmSize, String purchasingOption, boolean persistent, int vmCount) throws InterruptedException, ExecutionException, IOException, URISyntaxException, StorageException {
		 vmClusterHandler.setStorageAccount(this.storageAccount);
		 return vmClusterHandler.createVirtualMachinesCluster(this.resourceGroup.name(), vmSize, purchasingOption, persistent, vmCount, this.projectID, this.terminationQueueName, this.httpClient, getOAuthToken(), this.cloudStorageAccount);
	 }
	 
	 public int getVCPUsCount(String vmSize) {
		 return vmClusterHandler.getVCPUsCount(vmSize);
	 }
	 
	 public void downloadFLYProjectonVMCluster() throws InterruptedException, ExecutionException, IOException {
		 vmClusterHandler.downloadExecutionFileOnVMCluster(this.resourceGroup.name(), this.projectID, this.terminationQueueName, this.httpClient, getOAuthToken());
	 }
	 
	 public void buildFLYProjectOnVMCluster(String mainClass) throws Exception {
		 vmClusterHandler.buildFLYProjectOnVMCluster(this.projectID, this.terminationQueueName, this.httpClient, this.resourceGroup.name(), getOAuthToken(), mainClass);
	 }

	public String checkBuildingStatus() throws InvalidKeyException, URISyntaxException, StorageException, IOException {
		downloadFile("buildingOutput");
		String error = vmClusterHandler.checkBuildingStatus("buildingOutput");
		Path fileName = Path.of("buildingOutput");
		Files.deleteIfExists(fileName);
		return error;
	 }
	 
	 public void executeFLYonVMCluster(ArrayList<String> objectInputsString, int numberOfFunctions, long idExec) throws Exception {
		 ArrayList<String> urisBlob = writeInputObjectsToFileAndUploadToCloud(objectInputsString, vmClusterHandler.virtualMachines, numberOfFunctions);
		 vmClusterHandler.executeFLYonVMCluster(objectInputsString, urisBlob, numberOfFunctions, this.projectID, idExec, this.httpClient, this.resourceGroup.name(), getOAuthToken(), this.terminationQueueName);
	 }
	 
	 
	 public String checkForExecutionErrors() throws InvalidKeyException, URISyntaxException, StorageException, IOException {
		downloadFile("executionError");
		return vmClusterHandler.checkForExecutionErrors("executionError");
	 }

	 public void deleteResourcesAllocated() throws URISyntaxException, StorageException {
		 vmClusterHandler.deleteResourcesAllocated(this.resourceGroup.name(), false, this.cloudStorageAccount);
	 }
	 
	 private ArrayList<String> writeInputObjectsToFileAndUploadToCloud(ArrayList<String> objectInputsString, List<VirtualMachine> virtualMachines, int numberOfFunctions) throws InvalidKeyException, URISyntaxException, StorageException, IOException {
		
			int vmCountToUse = virtualMachines.size();
			if(numberOfFunctions < vmCountToUse) vmCountToUse = numberOfFunctions;
			
			ArrayList<String> urisBlob = new ArrayList<String>();
			
			//Check if the input is just a range of functions to execute
			if(objectInputsString.get(0).contains("portionRangeLength")) {
				//Range input
	
				//write files with splits input for each vm
				for (int i=0; i < vmCountToUse; i++) {
	
					File fout = new File("mySplits_"+virtualMachines.get(i).name()+".txt");
					FileOutputStream fos = new FileOutputStream(fout);
	
					BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
	
					bw.write(objectInputsString.get(i));
					bw.newLine();
					
					bw.close();
					
					urisBlob.add(uploadFile(fout));
					Files.delete(Path.of(fout.getName()));
				}
			}else {
				//Array or matrix split input
				//Specify how many splits each VM has to compute
				int splitsNum = objectInputsString.size();

				int[] splitCount = new int[vmCountToUse];
				int[] displ = new int[vmCountToUse]; 
				int offset = 0;

				for(int i=0; i < vmCountToUse; i++) {
					splitCount[i] = ( splitsNum / vmCountToUse) + ((i < (splitsNum % vmCountToUse)) ? 1 : 0);
					displ[i] = offset;
					offset += splitCount[i];
				}

				for (int i=0; i < vmCountToUse; i++) {

					File fout = new File("mySplits_"+virtualMachines.get(i).name()+".txt");
					FileOutputStream fos = new FileOutputStream(fout);

					BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));

					bw.write(String.valueOf(splitCount[i]));
					bw.newLine();
					
					//Select my part of splits
					for(int k=displ[i]; k < displ[i] + splitCount[i]; k++) {
						bw.write(objectInputsString.get(k));
						bw.newLine();
					}
					
					bw.close();
					
					urisBlob.add(uploadFile(fout));
					Files.delete(Path.of(fout.getName()));
				}
			}
			return urisBlob;
	 }

}