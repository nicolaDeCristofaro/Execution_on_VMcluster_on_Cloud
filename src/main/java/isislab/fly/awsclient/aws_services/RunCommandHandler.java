package isislab.fly.awsclient.aws_services;

import java.io.IOException;
import java.util.List;

import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.CommandInvocation;
import com.amazonaws.services.simplesystemsmanagement.model.CreateDocumentRequest;
import com.amazonaws.services.simplesystemsmanagement.model.DeleteDocumentRequest;
import com.amazonaws.services.simplesystemsmanagement.model.DocumentIdentifier;
import com.amazonaws.services.simplesystemsmanagement.model.GetCommandInvocationRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetCommandInvocationResult;
import com.amazonaws.services.simplesystemsmanagement.model.ListCommandInvocationsRequest;
import com.amazonaws.services.simplesystemsmanagement.model.ListCommandInvocationsResult;
import com.amazonaws.services.simplesystemsmanagement.model.ListDocumentsRequest;
import com.amazonaws.services.simplesystemsmanagement.model.ListDocumentsResult;
import com.amazonaws.services.simplesystemsmanagement.model.SendCommandRequest;
import com.amazonaws.services.simplesystemsmanagement.model.SendCommandResult;

public class RunCommandHandler {
	
	private AWSSimpleSystemsManagement ssm;
	private TransferManager tm;

	
	public RunCommandHandler(AWSSimpleSystemsManagement ssm, TransferManager tm) {
		this.ssm = ssm;
		this.tm = tm;
	}

	
	public void runOnVM(String instanceId, String bucketName, String projectName) {

		String docExecutionName = "commandsExecution";
		
		//Check if document with commands exist on AWS, if yes delete the old versions
		checkDocumentExists(docExecutionName);
		
	    //Create the new versions of commands documents
		try {
	      createDocumentMethod(getDocumentContent3(projectName, bucketName), docExecutionName);
	    }
	    catch (IOException e) {
	      e.printStackTrace();
	    }
		
		//Execution command
		System.out.println("\n\u27A4 Execution on Vm");
		
		String commandOutput = isCommandExecuted(instanceId, bucketName, docExecutionName, false);
		if (!commandOutput.equals("")) System.out.println(commandOutput);
		
		tm.shutdownNow();
		
	}
	
	public void waitForInstanceBoot(String instanceId) {
		String docCheckBootName = "commandsCheckBoot";
		
		
		System.out.print("\n\u27A4 Waiting for the boot instance script to complete..."); //Fist document commands

		//Check if documents with commands exist on AWS, if yes delete the old versions
		checkDocumentExists(docCheckBootName);
		
	    //Create the new versions of commands documents
		try {
	      createDocumentMethod(getDocumentContent1(), docCheckBootName);
	    }
	    catch (IOException e) {
	      e.printStackTrace();
	    }
				
		//Command 1:List files and check for the existence of 'boot-finishes' file
		String commandOutput = "";
		boolean bootFinished = false;
		
		while(true) {
			commandOutput = isCommandExecuted(instanceId, "", docCheckBootName, false);
				
			if (commandOutput.contains("boot-finished")) bootFinished = true;
			
			if(bootFinished) break; 
		}
		System.out.println("Done");

	}
	
	public void syncS3Bucket(String instanceId, String bucketName) {
		String syncDocName = "syncCommand";
		
		System.out.print("\n\u27A4 Syncing with S3 Bucket...");

		//Check if documents with commands exist on AWS, if yes delete the old versions
		checkDocumentExists(syncDocName);
		
	    //Create the new versions of commands documents
		try {
	      createDocumentMethod(getDocumentContent4(bucketName), syncDocName);
	    }
	    catch (IOException e) {
	      e.printStackTrace();
	    }
				
		//Command:Sync with S3 Bucket (useful when the instance is already booted)
		String commandOutput = "";		
		
		commandOutput = isCommandExecuted(instanceId, bucketName, syncDocName, false);
		if (!commandOutput.equals("")) System.out.println(commandOutput);
		System.out.println("Done");
	}
	
	private void checkDocumentExists(String commandsDocName) {
		
		boolean existsDoc = false;

		//Check if the document with the given name exists, if yes, delete it
		String nextToken = null;

	    do {
			ListDocumentsRequest request = new ListDocumentsRequest().withNextToken(nextToken);
			ListDocumentsResult results = ssm.listDocuments(request);
			
			List<DocumentIdentifier> docs = results.getDocumentIdentifiers();

		    for (DocumentIdentifier doc : docs) {
		    	if(doc.getName().equals(commandsDocName)) existsDoc = true;
		    }
		    nextToken = results.getNextToken();
	    } while (nextToken != null);

	    if(existsDoc) {
			//The document already exists so delete it
			DeleteDocumentRequest deleteDocRequest = new DeleteDocumentRequest().withName(commandsDocName);
			ssm.deleteDocument(deleteDocRequest);
	    }
		
	}
	
	private String isCommandExecuted(String instanceId, String bucketName, String commandsDocName, boolean outputToBucket) {
				
		//Send the commands described in the given document
		SendCommandRequest sendCommandRequest = new SendCommandRequest()
				.withInstanceIds(instanceId)
				.withDocumentName(commandsDocName);
		
		if (outputToBucket) {
			//Save the output in a S3 bucket
			sendCommandRequest.setOutputS3BucketName(bucketName);
			sendCommandRequest.setOutputS3KeyPrefix("buildingStatusOutput/");
		}
		
		SendCommandResult sendCommandResult = ssm.sendCommand(sendCommandRequest);
		String commandId = sendCommandResult.getCommand().getCommandId();
		
		GetCommandInvocationRequest getCommandInvocationRequest = new GetCommandInvocationRequest();
		getCommandInvocationRequest.setCommandId(commandId);
		getCommandInvocationRequest.setInstanceId(instanceId);
		
		//The waiter (AWSSimpleSystemsManagementWaiters) does not exists in SDK v 1.11.428 so I check manually
		boolean commandSent = false;
		while(true) {
			if (!commandSent) {
				//wait until the command is sent in order to access its state
				ListCommandInvocationsRequest listCommRequest = new ListCommandInvocationsRequest()
																		.withCommandId(commandId)
																		.withInstanceId(instanceId);
				
				ListCommandInvocationsResult listCommResult = ssm.listCommandInvocations(listCommRequest);
				List<CommandInvocation> cis = listCommResult.getCommandInvocations();

			    for (CommandInvocation ci : cis) {
			    	if(ci.getCommandId().equals(commandId)) {
			    		commandSent = true;
			    	}
			    }
			    
			    if (!commandSent) continue;
			}
			
			//The command is sent, now wait until is finished
			GetCommandInvocationResult getCommandInvocationResult = ssm.getCommandInvocation(getCommandInvocationRequest);
			String status = getCommandInvocationResult.getStatusDetails();
			if(status.equals("Success")) {
				if (outputToBucket) return commandId;
				else return getCommandInvocationResult.getStandardOutputContent();
			}else if(status.equals("InProgress") || status.equals("Delayed") || status.equals("Pending")) {
				try {
					Thread.sleep(3000);
					continue;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}else {
				System.out.println("There are problems executing this command, the status is:  "+ status);
				System.out.println(getCommandInvocationResult.getStandardOutputContent());
				System.out.println(getCommandInvocationResult.getStandardErrorContent());
				break;
			}
		}
		return "";
	}
	
	private static String getDocumentContent1() throws IOException {
		return "---" + "\n"
			+ "schemaVersion: '2.2'" + "\n"
			+ "description: Check if the instance boot script is finished." + "\n"
			+ "parameters: {}" + "\n"
			+ "mainSteps:" + "\n"
			+ "- action: aws:runShellScript" + "\n"
			+ "  name: checkBoot" + "\n"
			+ "  inputs:" + "\n"
			+ "    runCommand:" + "\n"
			+ "    - cd ../../../../var/lib/cloud/instance"+ "\n"
			+ "    - ls" + "\n";
	}

	private static String getDocumentContent3(String projectName, String bucketName) throws IOException {
		return "---" + "\n"
			+ "schemaVersion: '2.2'" + "\n"
			+ "description: Execute FLY application." + "\n"
			+ "parameters: {}" + "\n"
			+ "mainSteps:" + "\n"
			+ "- action: aws:runShellScript" + "\n"
			+ "  name: execution" + "\n"
			+ "  inputs:" + "\n"
			+ "    runCommand:" + "\n"
			+ "    - aws s3 rb s3://"+bucketName+" --force > deleteBucketOutput.txt" + "\n"
			+ "    - cd ../../../../home/ubuntu"+ "\n"
			+ "    - java -jar "+projectName+ "\n"
			+ "    - rm -rf ..?* .[!.]* *"+ "\n";
	}
	
	private static String getDocumentContent4(String bucketName) throws IOException {
		return "---" + "\n"
			+ "schemaVersion: '2.2'" + "\n"
			+ "description: Syncing S3 Bucket." + "\n"
			+ "parameters: {}" + "\n"
			+ "mainSteps:" + "\n"
			+ "- action: aws:runShellScript" + "\n"
			+ "  name: syncing" + "\n"
			+ "  inputs:" + "\n"
			+ "    runCommand:" + "\n"
			+ "    - cd ../../../../home/ubuntu"+ "\n"
			+ "    - aws s3 sync s3://"+bucketName+" ." + "\n";		
	}
	
	private void createDocumentMethod (final String documentContent, String docName) {
	      final CreateDocumentRequest createDocRequest = new CreateDocumentRequest()
	    		  .withContent(documentContent)
	    		  .withName(docName)
	    		  .withDocumentType("Command")
	    		  .withDocumentFormat("YAML"); //The alternative is JSON
	      
	      ssm.createDocument(createDocRequest);
	    }

}
