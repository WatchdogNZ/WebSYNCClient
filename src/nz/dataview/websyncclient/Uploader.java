/**
 * WebSYNC Client Copyright 2007, 2008 Dataview Ltd
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software 
 * Foundation, either version 3 of the License, or (at your option) any later 
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS 
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * A copy of the GNU General Public License version 3 is included with this 
 * source distribution. Alternatively this licence can be viewed at 
 * <http://www.gnu.org/licenses/>
 */
package nz.dataview.websyncclient;

import java.util.zip.DeflaterInputStream;

import java.security.NoSuchAlgorithmException;

import org.apache.log4j.Logger;
import java.io.*;
import java.util.*;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.regex.Pattern;
import org.apache.log4j.NDC;

/**
 * The uploader thread.  Performs upload of data from the upload dir, specified
 * in the application configuration. Uses the batch processing mechanism as
 * defined in the New Zealand SMS-LMS interoperability specification 2
 * 
 * @author  William Song, Tim Owens
 * @version 2.1.2
 */
public class Uploader extends Thread {

   /**
    * The logger!
    */
   public static Logger logger = Logger.getLogger(Uploader.class);
   /**
    * The Client which spawned this thread.
    */
   public Client parent;
   /**
    * The path to the directory at which the files to be uplaoded reside.
    */
   public String uploadDir;
   /**
    * Counts used by uploader to record file upload progress
    */
   public int warnings;
   public int successfulUploads;
   public int failedUploads;
   /**
    * Gives the current status of the upload. Only used for a batch upload
    */
   public String status = "";
   
   /**
    * The current batch number
    */
   public String batchNumber="";

   private String batchFileName;
   private String batchXmlFileName;
   private int batchIndex;
   private int batchXmlIndex;
   private boolean convertBatch;
   HashMap<String,Boolean> batch_files =new HashMap<String,Boolean>(0);
   
   /**
    * Constructor.
    * 
    * @param   c  the Client which spawned this thread
    */
   public Uploader(Client c) {
      if (logger.isTraceEnabled()) {
         logger.trace("Entered Uploader.contructor() with Client: " + c);
      }
      parent = c;
      reloadConfig();

      if (logger.isTraceEnabled()) {
         logger.trace("Exiting Uploader.constructor()");
      }
   }

    /**
    * Refreshes the current variables with new values (if applicable) from the <code>Client</code>'s 
    * configuration file, which could have been updated.
    */
   public void reloadConfig() {
      if (logger.isTraceEnabled()) {
         logger.trace("Entered Uploader.reloadConfig()");
      }
      uploadDir = parent.getUploadDir();

      if (logger.isTraceEnabled()) {
         logger.trace("Exiting Uploader.reloadConfig()");
      }
   }

   public void getStatus() {
      try {
         FileInputStream fis = new FileInputStream(parent.controlDirectory + File.separator + "websync_status.txt");
         BufferedReader br = new BufferedReader(new InputStreamReader(fis));

         String line = "";
         line = br.readLine();
         if(line!=null)
			{
				status = line.trim();
			} else
			{
				status="";
			}
         line = br.readLine();
         if(line!=null)
			{
				batchNumber = line.trim();
			} else batchNumber = "";
         fis.close();
      } catch (FileNotFoundException e) {
         //No need for an error.
      } catch (IOException e) {
         logger.error("0302: Could not read status file.");
			if(logger.isDebugEnabled())
			{
				logger.debug("0302: Could not read status file:\n" + e.getMessage());
			}
      }
  }

   /**
    * Given an array of files in the upload folder, we need to identify the
    * batch file, which could be in three different states depending on the
    * status of the upload.
    *
    * While we're at it, load the status file if found.
    *
    * @param files
    */
   private void huntForBatch(File[] files) {
      Pattern startMatch = Pattern.compile("^start_(\\d{12,14})\\.txt$", java.util.regex.Pattern.CASE_INSENSITIVE);
      Pattern xmlMatch = Pattern.compile("^batch_index_(\\d{12,14})\\.xml$", java.util.regex.Pattern.CASE_INSENSITIVE);
      String batchindex="";
      
      batchFileName = "";
      batchXmlFileName = "";
      batchIndex = -1;
      batchXmlIndex = -1;
		convertBatch = false;
      Calendar rightNow = Calendar.getInstance();
      
      for (int i = 0; i < files.length; i++) {
         String filename = files[i].getName();
         java.util.regex.Matcher m=xmlMatch.matcher(filename);
         if (m.find() && files[i].lastModified()+6000 < rightNow.getTimeInMillis()) {
            //Pick the earliest batch
            if(batchindex.equals("") || batchindex.compareTo(m.group(1))>0)
            {
               batchXmlFileName = filename;
               batchXmlIndex = i;
               batchindex=m.group(1);
            }
         }
      }
      if (batchXmlIndex == -1) {
         //Do we have a new batch?
         for (int i = 0; i < files.length; i++) {
            String filename = files[i].getName();
            java.util.regex.Matcher m = startMatch.matcher(filename);
            if (m.find() && files[i].lastModified()+6000 < rightNow.getTimeInMillis()) {
               //Pick the earliest batch
               if (batchindex.equals("") || batchindex.compareTo(m.group(1)) > 0) {
                  batchFileName = filename;
                  batchIndex = i;
                  convertBatch = true;
                  batchindex=m.group(1);
               }
            }
         }
      }
      if (!batchXmlFileName.equals("") || !batchFileName.equals("")) {
         //Find all the batch files
         batch_files.clear();
         try{
            String bf;
            if(!batchXmlFileName.equals(""))
            {
               bf=uploadDir + File.separator + batchXmlFileName;
            } else
            {
               bf=uploadDir + File.separator + batchFileName;
            }
            FileInputStream fis = new FileInputStream(bf);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis));

            String line = "";
            Integer i=0;
            while ((line = br.readLine()) != null) {
               String[] parts=line.split(" ");
               if(!parts[0].trim().equals(""))
               {
                  batch_files.put(parts[0].trim(), true);
               }
            } 
            br.close();
            fis.close();
         } catch (java.io.FileNotFoundException e) {
            String message = "Could not find batch xml file " + batchXmlFileName;
            logger.error("0322: Could not find batch xml file " + batchXmlFileName);
            if (logger.isDebugEnabled()) {
               logger.debug("0322: Could not find batch xml file: \n"+e.getMessage());
            }
            parent.appendReport(message);
            parent.badOverallStatus();
         } catch (java.io.IOException e) {
            String message = "Error reading batch xml file " + batchXmlFileName;
            logger.error("0323: Error reading batch xml file " + batchXmlFileName);
            if (logger.isDebugEnabled()) {
               logger.debug("0323: Error reading batch xml file: \n"+e.getMessage());
            }
            parent.appendReport(message);
            parent.badOverallStatus();
         }
      }
   }

   /**
    * Looks in the upload_dir specified in the config, and if there are files in there,
    * and the files have not been marked to not upload, will upload to the web server.
    */
   public void run() {
      int i;
		String knstat;
      boolean moreFilesFound=false;

      NDC.push("Uploader");

      if (logger.isTraceEnabled()) {
         logger.trace("Entered Uploader.run()");
      }
      if (logger.isDebugEnabled()) {
         logger.debug("uploadDir: " + uploadDir);
      }
      File root = null;
      try {
         root = new File(uploadDir);
      } catch (Exception e) {
         logger.error("0304: Upload folder could not be read from config.");
      }
      successfulUploads = 0;
      failedUploads = 0;
      warnings = 0;
		try{
			HashMap h=(java.util.HashMap)parent.knStatus;

			Object val=h.get("status");
			knstat=val.toString();
		} catch(Exception e)
		{
			knstat="";
		}

      getStatus();

      abort:
      do {
         if (!status.equals("waiting for response")) {
				error:
				do {
					if (root.isDirectory() && root.canRead()) {
						if (logger.isDebugEnabled()) {
							logger.debug("Directory (" + uploadDir + ") is readable, about to loop through files");
						}
						File[] files = root.listFiles();

						//Do we have a batch file, or are we already processing one?
                  huntForBatch(files);
						if (convertBatch) {
							//Convert the file to a batch_index, and an XML file
							File batchFile = new File(uploadDir + File.separator + "batch_index" + batchFileName.substring(5));
							boolean success = files[batchIndex].renameTo(batchFile);
							if (!success) {
								logger.error("0305: Could not rename batch file " + batchFileName);
								parent.appendReport("Could not rename batch file " + batchFileName);
								parent.badOverallStatus();

								break error;
							}
							batchFileName = batchFile.getName();
							String batchXMLName = "batch_index" + batchFileName.substring(11, batchFileName.length() - 4) + ".xml";
							try {
								Pattern batchNumberMatch = Pattern.compile("^.*?(\\d{12,14}).*?$");
								java.util.regex.Matcher batchNumberMatcher = batchNumberMatch.matcher(batchFileName);
								batchNumberMatcher.find();
								batchNumber = batchNumberMatcher.group(1);
                        parent.updateBatchNumber(batchNumber);

								PrintStream ps = new PrintStream(new FileOutputStream(uploadDir + File.separator + batchXMLName));
								FileInputStream fis = new FileInputStream(uploadDir + File.separator + batchFileName);
								BufferedReader br = new BufferedReader(new InputStreamReader(fis));

								String line = "";
								while ((line = br.readLine()) != null) {
									ps.println(line);
								}

								fis.close();
								ps.close();
                        File batchfile=new File(uploadDir + File.separator + batchXMLName);
                        batchfile.setWritable(true);
							} catch (java.io.FileNotFoundException e) {
								String message = "Could not create batch xml file " + batchXMLName;
								logger.error("0306: Could not create batch xml file " + batchXMLName);
								if (logger.isDebugEnabled()) {
									logger.debug("0306: Could not create batch xml file: \n"+e.getMessage());
								}
								parent.appendReport(message);
								parent.badOverallStatus();
								break error;
							} catch (java.io.IOException e) {
								String message = "Error writing batch xml file " + batchXMLName;
								logger.error("0307: Error writing batch xml file " + batchXMLName);
								if (logger.isDebugEnabled()) {
									logger.debug("0307: Error writing batch xml file: \n"+e.getMessage());
								}
								parent.appendReport(message);
								parent.badOverallStatus();
								break error;
							}

                     try{
                        PrintStream ps = new PrintStream(new FileOutputStream(uploadDir + File.separator + batchNumber + "_snapshot.txt"));
								for (i = 0; i < files.length; i++)
                        {
                           ps.println(files[i].getName());
                        }
                        ps.close();
                        files = root.listFiles();
                     } catch(FileNotFoundException e)
                     {
								logger.error("0307: Error writing batch snapshot file " + batchNumber + "_snapshot.txt");
                     }
                             
							huntForBatch(files);
						}

						if (batchXmlIndex >= 0 )
						{
							if(!knstat.equals("") && !knstat.equals("complete") && !knstat.equals("error"))
							{
								logger.warn("Batch still being processed.");
                        logdebug("1a");
							} else
							{
								if (logger.isDebugEnabled()) {
									logger.debug("files to upload: " + files.length);
								}
                        logdebug("1b" + files.length);
								//Try to upload each of the files in turn, but don't upload the batch file(s) or status file
								for (i = 0; i < files.length; i++) {
                           Object file_in_batch=batch_files.get(files[i].getName());
									if (file_in_batch!=null && file_in_batch.equals(true)) {
										uploadFile(files[i]);
										try{
											//Yes, we're still alive!
											parent.sendMessage("is_up");
											parent.sendMessage("is_running");
										}catch(IOException e){}
									}
								}
                                //Just in case any have arrived afterwards
                                moreFilesFound=false;
                                files = root.listFiles();
                                huntForBatch(files);
								for (i = 0; i < files.length; i++) {
                                    Object file_in_batch=batch_files.get(files[i].getName());
									if (file_in_batch!=null && file_in_batch.equals(true)) {
                                        moreFilesFound=true;
                                        break;
                                    }
                                }
                                logdebug("1c" + (moreFilesFound?"1":"0"));
								//If all the files uploaded fine, send the batch file.
								if(parent.getOverallStatus() && !moreFilesFound)
								{
									 //Lastly, upload the XML file.
									 uploadFile(files[batchXmlIndex]);

									if (parent.getOverallStatus()) {
										if (!parent.updateStatus("waiting for response")) {
											break error;
										}
									} else {
										parent.updateStatus("trying again later");
									}
								} else {
									parent.updateStatus("trying again later");
								}
							}
						} else {
							logger.debug("No batch file found.");
                     
							if(!knstat.equals("") && !knstat.equals("complete") && !knstat.equals("error"))
							{
								logger.debug("Batch still being processed.");
							}
							break abort;
						}
					} else {
						// oh dear!
						String message = "Could not read the upload dir (" + uploadDir + ")";
						logger.error("0308: Could not read the upload dir (" + uploadDir + ")");
						parent.appendReport(message);
						parent.badOverallStatus();

						break error;
					}
				} while (false);

				if(successfulUploads+failedUploads+warnings>0)
				{
					String overview = "Overview of upload run: ";
					overview += "successful uploads: " + successfulUploads;
					overview += ", failed uploads: " + failedUploads;
					overview += ", warnings: " + warnings;
					overview += ", more files found: " + moreFilesFound;
					logger.info("Completed upload run (" + overview + ")");
					parent.appendReport(overview);
				}

				if (failedUploads > 0) {
					logger.error("0309: "+failedUploads + " file(s) failed to upload during this run");
				}
				if (warnings > 0) {
					logger.warn(warnings + " warning(s) were detected during this run");
				}
         } else {
            //Check for success/fail
            WebSYNCService caller = WebSYNCServiceFactory.getSoapService(
                    parent.getKnUrl(),
                    "http://www.dataview.co.nz/",
                    parent.getAuthenticationKey(),
                    parent.getSchoolName(),
                    parent.getSchoolNumber(),
                    parent.getScheduleUploadString(),
                    parent.getProcessTimeString());

            String result = caller.doGetBatchResult();
            if (logger.isDebugEnabled()) {
               logger.debug("Got result: " + result);
            }
            if (result.equals("SUCCESS")) {
               //Create success message batchnumber-success.txt
               try {
                  PrintStream ps = new PrintStream(new FileOutputStream(uploadDir + File.separator + batchNumber + "-success.txt"));
                  ps.println(" ");
                  ps.close();
                  ps = new PrintStream(new FileOutputStream(parent.controlDirectory + File.separator+ "last_response.txt"));
                  ps.println("SUCCESS");
                  ps.println(batchNumber);
                  ps.close();
                  File resp=new File(uploadDir + File.separator + batchNumber + "-success.txt");
                  resp.setWritable(true);
                  parent.updateStatus("");
                  //parent.updateBatchNumber("");
						logger.info("Batch " + batchNumber + " completed successfully.");
               } catch (java.io.FileNotFoundException e) {
                  String message = "Could not create success file " + uploadDir + File.separator + batchNumber + "-success.txt";
						if (logger.isDebugEnabled()) {
							logger.debug("0310: Could not create success file:\n" + e.getMessage());
						}
						logger.error("0310: Could not create success file " + uploadDir + File.separator + batchNumber + "-success.txt");
                  parent.appendReport(message);
                  parent.badOverallStatus();
               } catch (java.io.IOException e) {
						if (logger.isDebugEnabled()) {
							logger.debug("0311: Error writing success file:\n" + e.getMessage());
						}
						logger.error("0311: Error writing success file " + uploadDir + File.separator + batchNumber + "-success.txt");
                  String message = "Error writing success file " + uploadDir + File.separator + batchNumber + "-success.txt";
                  logger.error(message);
                  parent.appendReport(message);
                  parent.badOverallStatus();
               }
            } else if (result.equals("")) {
               //Not finished
            } else {
               //Create fail message (batchnumber-reject.txt)
               try {
                  PrintStream ps = new PrintStream(new FileOutputStream(uploadDir + File.separator + batchNumber + "-reject.txt"));
                  ps.print(result);
                  ps.close();
                  ps = new PrintStream(new FileOutputStream(parent.controlDirectory + File.separator + "last_response.txt"));
                  ps.println("FAILED");
                  ps.println(batchNumber);
                  ps.close();
                  File resp=new File(uploadDir + File.separator + batchNumber + "-reject.txt");
                  resp.setWritable(true);
                  parent.updateStatus("");
                  //parent.updateBatchNumber("");
						logger.error("0301: Batch " + batchNumber + " failed.");
						if (logger.isDebugEnabled()) {
							logger.debug("0301: Batch " + batchNumber + " failed with response: \n"+result);
						}
               } catch (java.io.FileNotFoundException e) {
                  String message = "Could not create failure file " + uploadDir + File.separator + batchNumber + "-reject.txt";
						if (logger.isDebugEnabled()) {
							logger.debug("0312: Could not create failure file:\n" + e.getMessage());
						}
						logger.error("0312: Could not create failure file " + uploadDir + File.separator + batchNumber + "-reject.txt");
                  parent.appendReport(message);
                  parent.badOverallStatus();
               } catch (java.io.IOException e) {
						if (logger.isDebugEnabled()) {
							logger.debug("0313: Error writing failure file:\n" + e.getMessage());
						}
						logger.error("0313: Error writing failure file " + uploadDir + File.separator + batchNumber + "-reject.txt");
                  String message = "Error writing failure file " + uploadDir + File.separator + batchNumber + "-reject.txt";
                  parent.appendReport(message);
                  parent.badOverallStatus();
               }
            }
         }
      } while (false);

      logger.trace("Exiting Uploader.run()");

      NDC.pop();
      NDC.remove();
   }
   
   public void logdebug(String message)
   {
      try{
         PrintStream ps = new PrintStream(new FileOutputStream(uploadDir + File.separator + batchNumber + "_logdebug.txt",true));
         ps.print(message);
         ps.close();
      } catch (Exception e) {}
   }

   /**
    * Checks if the file is a file, readable, writeable, uploadable, and then calls doUpload to upload it.
    * @param file
    */
   public void uploadFile(File file) {
      String filename = file.getName();

      if (logger.isDebugEnabled()) {
         logger.debug("Got filename: " + filename);      // the exceptions which should not be uploaded
      }
      logdebug("2a[" + filename+"]");
      if (file.isDirectory()) {
         return;
      }

      if (logger.isDebugEnabled()) {
         logger.debug("File is an ordinary file, proceed to upload checks");
      }
      logdebug("2b");
      if (file.canRead()) {
         if (logger.isDebugEnabled()) {
            logger.debug("File " + filename + " is readable");
         }
         logdebug("2c");
         try {

            boolean doDelete = doUpload(file);
            if (logger.isDebugEnabled()) {
               logger.debug("Returned from doUpload invocation with: " + doDelete);
            }
            logdebug("2d[" + doDelete+"]");
            if (doDelete) {
               logger.debug("File " + filename + " was uploaded to the server");
               logdebug("2e");

					if (!file.canWrite()) {
						String message = "0314: Could not delete uploaded file " + filename + " (insufficient permissions)";
						logger.error(message);
                  logdebug("2f");
						parent.appendReport(message);
						parent.badOverallStatus();
						warnings++;

						return;
					}
               // attempt to delete the file
               boolean deleteDone = file.delete();
               if (logger.isDebugEnabled()) {
                  logger.debug("Attempted to delete file " + filename);               //deleteDone = false;
               }
               if (!deleteDone) {
                  String message = "0315: Could not delete uploaded file " + filename;
                  logger.error(message);
                  logdebug("2g");
                  parent.badOverallStatus();
                  parent.appendReport(message);
                  warnings++;
               } else {
                  logger.debug("Uploaded file " + filename + " was deleted from disk");
                  logdebug("2h");
                  logger.info("File upload of " + filename + " completed successfully");
                  successfulUploads++;
               }
            } else if (!doDelete) {
               String message = "0316: Failed to upload file " + filename;
               logger.error(message);
               logdebug("2i");
               parent.appendReport(message);
               parent.badOverallStatus();
               failedUploads++;
               return;

            }
         } catch (FileNotFoundException e) {
            // something weird is going on
            String message = "File " + filename + " could not be found (" + e.getMessage() + ")";
            logger.error("0317: File " + filename + " could not be found.");
				if (logger.isDebugEnabled()) {
					logger.debug("0317: File " + filename + " could not be found:\n"+ e.getMessage());
				}
            logdebug("2j(" + e.getMessage() + ")");
            parent.appendReport(message);
            parent.badOverallStatus();
            failedUploads++;
            return;
         }
      } else {
         String message = "Could not read file " + filename + " for upload.";
         logger.error("0318: Could not read file " + filename + " for upload.");
         logdebug("2k");
         parent.appendReport(message);
         parent.badOverallStatus();
         failedUploads++;
         return;
      }
      logdebug("\n");
   }

   /**
    * Performs the upload for the given file. Compresses the data in transit using the RFC 1951 (GZIP/ZLIB) standard
    * 
    * @param   f	the File to upload
    * @return  indicates whether the file was uploaded successfully and should therefore be deleted or not
    * @throws  java.io.FileNotFoundException thrown if IO error occurred while opening the given file
    */
   public boolean doUpload(File f) throws FileNotFoundException {
      if (logger.isTraceEnabled()) {
         logger.trace("Entered Uploader.doUpload() with File: " + f);
      }
      boolean doDelete = false;
      boolean abort = false;
      
      do {
         boolean completed = false;

         if (f.canRead()) {
            if (logger.isDebugEnabled()) {
               logger.debug("File " + f + " is readable");
            }
            logdebug("3a[" + f.getName() + "]");
            int limit = parent.getUploadByteLimit();
            //A file input stream, a buffered input stream, and a deflater input stream. Oh the joys of Java.
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
            DeflaterInputStream dis = new DeflaterInputStream(bis);

            byte[] data = new byte[limit];

            if (logger.isDebugEnabled()) {
               logger.debug("Got upload byte limit: " + limit);
            }
            int recId = 0;	//first call should have recId of 0 to indicate new record
            String filename = f.getName();

            WebSYNCService caller = WebSYNCServiceFactory.getSoapService(
                  parent.getKnUrl(),
                  "http://www.dataview.co.nz/",
                  parent.getAuthenticationKey(),
                  parent.getSchoolName(),
                  parent.getSchoolNumber(),
                  parent.getScheduleUploadString(),
                  parent.getProcessTimeString());

            int bytesSoFar = 0;
            int numBytesRead = 0;
            int blockNum = 1;

            boolean isFatal = false;
            try {
               while ((numBytesRead = dis.read(data, 0, limit)) != -1) {
                  if (logger.isDebugEnabled()) {
                     logger.debug("Read " + limit + " bytes from byte no: " + bytesSoFar + ", numBytesRead: " + numBytesRead);               // if we are at the end of the byte stream, trim the data array to size
                  }
                  logdebug("3b["+limit + "|" + bytesSoFar + "|" + numBytesRead + "]");
                  if (numBytesRead < limit) {
                     byte[] tempData = data;
                     data = new byte[numBytesRead];
                     System.arraycopy(tempData, 0, data, 0, numBytesRead);
                     tempData = null;

                     if (logger.isDebugEnabled()) {
                        logger.debug("End of the byte stream detected, copy data array to trimmed array");
                     }
                     logdebug("3c");
                  }

                  String[] dataToSend = Utilities.encodeForUpload(data);
                  if (logger.isDebugEnabled()) {
                     logger.debug("Split into chunks of String of length 76 successfully");
                     logger.debug("Data block prepared for upload");
                  }
                  logdebug("3d");

                  // make the call!
                  int count = 0;
                  boolean hasError = false;
                  do {
                     hasError = false;
                     isFatal = false;

                     if (logger.isDebugEnabled()) {
                        logger.debug("About to upload block " + blockNum + " (attempt: " + (count + 1) + " of 50)");
                     }
                     logdebug("3e[" + blockNum + "|" + (count + 1) + "]");
                     int temptRecId = caller.doUploadBlock(filename, dataToSend, recId, blockNum);

                     if (logger.isDebugEnabled()) {
                        logger.debug("Returned from invocation with: " + temptRecId);                  // if something went wrong
                     }
                     logdebug("3f["+temptRecId+"]");
                     if (temptRecId == -1 || temptRecId == 0) {
                        hasError = true;
                        // try and find the last successful block
                        logger.info("Block " + blockNum + " failed to upload, attempting to recover");
                        logdebug("3g");
                        if(recId==0)
                        {
                           logger.debug("Failed to upload the first block. Try again.");
                           logdebug("3h");
                        } else
                        {
                           int lastBlockNum = caller.doUploadGetLastBlock(recId);
                           logger.debug("Last successfully uploaded block: " + lastBlockNum + ", current block: " + blockNum);
                           logdebug("3i["+lastBlockNum+"]");
                           // if the last successful upload was the not previous block, then this is not recoverable
                           if (lastBlockNum == blockNum) {
                              logger.debug("Block was actually uploaded. Moving on.");
                              logdebug("3j");
                              bytesSoFar += limit;
                              blockNum++;
                           } else if(lastBlockNum == blockNum - 1) {
                              logger.debug("Block is recoverable, trying again");
                              logdebug("3k");
                           } else
                           {
                              logger.debug("Sequence broken. Aborting.");
                              logdebug("3l");
                              isFatal=true;
                           }
                        }
                        try {
                           Thread.sleep(500);
                        } catch (InterruptedException e) {
                        }
                     } else {
                        // block uploaded successfully
                        recId = temptRecId;
                        bytesSoFar += limit;
                        blockNum++;
                     }

                     count++;
                  } while (count < 50 && (hasError && !isFatal)); // if doUpload returns -1, something went wrong, try again
                  if (count >= 50 && hasError) {
                     isFatal = true;
                  }
                  if (isFatal) {
                     // the block upload failed, quit this file
                     String message = "Failed to upload file: " + filename + " (uploaded so far: " + bytesSoFar + " bytes), gave up after 5 tries";
                     logger.error("0319: Failed to upload file: " + filename + " (uploaded so far: " + bytesSoFar + " bytes), gave up after 5 tries");
                     logdebug("3m["+bytesSoFar+"]");
                     parent.appendReport(message);
                     parent.badOverallStatus();
                  dis.close();
                     bis.close();
                     break;
                  } else {
                     if (logger.isDebugEnabled()) {
                        logger.debug("Data block uploaded successfully, after " + count + " attempts");
                     }
                     logdebug("3n["+count+"]");
                  }

                  // clear the byte buffer
                  data = new byte[limit];

                  parent.sendMessage("is_up");
                  parent.sendMessage("is_running");
               }

               // do not close off the file if a fatal error occurred
               if (!isFatal) {
                  // Indicate that the file is complete and can be uncompressed
                  int finalCount = 0;
                  int ret = 0;
                  do {
                     if (logger.isDebugEnabled()) {
                        logger.debug("About to call final upload to close off sequence (attempt: " + (finalCount + 1) + " of 10)");
                     }
                     logdebug("3o["+(finalCount + 1)+"]");
                     ret = caller.doUncompressFile(recId);

                     if (logger.isDebugEnabled()) {
                        logger.debug("Returned from uncompress invocation with: " + ret);
                     }
                     logdebug("3p["+ret+"]");
                     finalCount++;
                  } while (finalCount < 10 && ret <= 0);

                  if (ret == -1) {
                     String message = "Failed to close off sequence, gave up after 50 tries";
                     logger.error("0320: Failed to close off sequence, gave up after 50 tries");
                     logdebug("Failed to close off sequence, gave up after 50 tries");
                     parent.appendReport(message);
                     parent.badOverallStatus();

                     if (logger.isDebugEnabled()) {
                        logger.debug("Appended error to report, set overall status to bad");
                     }
                     logdebug("3q");
                  } else if (!isFatal) {
                     if (logger.isDebugEnabled()) {
                        logger.debug("Sequence closed off successfully");
                        logger.debug("File " + filename + " uploaded successfully");
                     }
                     logdebug("3r");
                     completed = true;
                  }
                  dis.close();
                  bis.close();

                  if(completed)
                  {
                     // now check the integrity of the uploaded file (and close it at the other end)
                     boolean validFile = false;
                     try {
                        byte[] byteList = Utilities.getBytesFromFile(f);
                        validFile = isFileValid(recId, byteList);
                     } catch (IOException e) {
                        logger.warn("Failed to read file for upload integrity check: " + e.getMessage());
                        logdebug("3s " + e.getMessage());
                     }

                     if (!validFile) {
                        String message = "Uploaded file integrity check failed";
                        logdebug("3t");
                        logger.warn(message);
                        parent.appendReport(message);
                        parent.badOverallStatus();
                     } else {
                        doDelete = true;
                        logger.debug("Uploaded file integrity checked successfully");
                        logdebug("3u");
                     }
                  }
               }
            } catch (IOException e) {
               String message = "IO error encountered while reading file: " + filename + " to upload (uploaded so far: " + bytesSoFar + " bytes), giving up.";
               logger.error("0321: IO error encountered while reading file: " + filename + " to upload (uploaded so far: " + bytesSoFar + " bytes), giving up.");
               if (logger.isDebugEnabled()) {
                  logger.debug("0321: IO error encountered while reading file:\n"+e.getMessage());
               }
               logdebug("3w[" + bytesSoFar + "]");
               parent.appendReport(message);
               parent.badOverallStatus();
               
               abort=true;
            }
         } else
         {
            String message = "File is not readable: " + f.getName() + ".";
            logger.error("0324: File is not readable: " + f.getName() + ".");
            logdebug("3x[" + f.getName() + "]");
            parent.appendReport(message);
            parent.badOverallStatus();

            abort=true;
         }
      } while (!doDelete && !abort);

      if (logger.isTraceEnabled()) {
         logger.trace("Exiting Uploader.doUpload() with boolean: " + doDelete);
      }
      logdebug("3y[" + doDelete + "]");
      return doDelete;
   }

   /**
    * Returns a String representation of this object.
    * 
    * @return  the string representation of this object
    */
   @Override
   public String toString() {
      StringBuffer sb = new StringBuffer();
      sb.append(getClass().getName());
      sb.append(" uploadDir=[").append(this.uploadDir).append("]");
      return sb.toString();
   }

   /**
    * Determines whether uploaded file is valid.
    * 
    * @param   fileId	the ID of the file uploaded
    * @param   data	the file contents
    * @return		true if valid, false if not
    */
   private boolean isFileValid(int fileId, byte[] data) {
      if (logger.isTraceEnabled()) {
         logger.trace("Entered Uploader.isFileValid() with int: " + fileId + ", byte[]: " + data.length);
      }
      if (data == null) return false;
      if (data.length == 0) {
         if (logger.isDebugEnabled()) {
            logger.debug("No data to check for consistency, assume data is already consistent");
         }
         return true;
      }

      boolean ret = false;
      WebSYNCService caller = WebSYNCServiceFactory.getSoapService(
              parent.getKnUrl(),
              "http://www.dataview.co.nz/",
              parent.getAuthenticationKey(),
              parent.getSchoolName(),
              parent.getSchoolNumber(),
              parent.getScheduleUploadString(),
              parent.getProcessTimeString());

      try {
         String localHash = Utilities.MD5(data);
         if (logger.isDebugEnabled()) {
            logger.debug("Got local hash: " + localHash);
         }
         String remoteHash = caller.doGetUploadMD5Hash(fileId,localHash);
         if (logger.isDebugEnabled()) {
            logger.debug("Got remote hash: " + remoteHash);
         }
         ret = (localHash.length()==32 && localHash.equals(remoteHash));
      } catch (NoSuchAlgorithmException e) {
         logger.warn("MD5 algorithm is unavailable: " + e);
      } catch (UnsupportedEncodingException e) {
         logger.warn("Encoding unavailable: " + e);
      }

      if (logger.isTraceEnabled()) {
         logger.trace("Exiting Uploader.isFileValid() with boolean: " + ret);
      }
      return ret;
   }
}
