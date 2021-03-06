package jes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import jes.ftp.JESClient;
import jes.ftp.JESJob;
import jes.ftp.JESSpoolFile;

public class Application {
	
	private CommandLine commandLine;
	private String delimiterCSV;
	private JESClient clientJES;
	
	private Options createOptions() {
        Options options = new Options();

        options.addOption("h", "help", false, "print this help and exit");

        options.addOption("s", "hostname", true, "FTP hostname");
        options.addOption("u", "username", true, "FTP username");

        Option option = new Option("p", "password", true, "FTP password");
        option.setRequired(true);
        options.addOption(option);

        options.addOption("o", "owner", true, "job owner filter");
        options.addOption("j", "jobname", true, "job name filter");

        options.addOption("l", "list-jobs", false, "list jobs");
        options.addOption("e", "detailed", false, "detailed jobs list");

        options.addOption("i", "list-spool", false, "list spool files");
        options.addOption("r", "read-spool", false, "read spool files");

        options.addOption("b", "submit", false, "submit job");
        options.addOption("f", "filename", true, "filename");
        options.addOption("d", "dataset", true, "dataset");

        options.addOption("w", "wait", false, "wait");
        options.addOption("g", "purge", false, "purge");
        
        options.addOption("v", "verbose", false, "enable verbose mode");
        options.addOption("c", "hide-table-columns", false, "hide table columns");
        options.addOption("x", "format-as-csv", false, "use CSV format");
        options.addOption("m", "csv-delimiter", true, "CSV delimiter. If not specified then \",\" will be used");
        
        return options;
    }
    
    private TextTable getJobTable() {
        TextTable jobTable = new TextTable(!commandLine.hasOption("hide-table-columns"), commandLine.hasOption("format-as-csv"), delimiterCSV);

        jobTable.addColumn("Name", 8);
        jobTable.addColumn("Identifier", 8);
        jobTable.addColumn("Owner", 8);
        jobTable.addColumn("Status", 6);
        jobTable.addColumn("Class", 5);
        jobTable.addColumn("Completion", 10);
        jobTable.addColumn("Spool files", 4);
        
        return jobTable;
    }

    private String formatJobs(List<JESJob> jobs) throws IOException {
        TextTable jobTable = getJobTable();

        for (JESJob jobJES : jobs) {
            String completion = "";
            if (jobJES.conditionCode != null) {
                completion = String.format("CC=%d", jobJES.conditionCode);
            } else if (jobJES.abendCode != null) {
                completion = String.format("ABEND=%d", jobJES.abendCode);
            }

            jobTable.addRow(jobJES.name, jobJES.handle, jobJES.owner, jobJES.status, jobJES.type, completion,
                    jobJES.spoolFileCount);
        }

        return jobTable.format();
    }

    private String formatSpool(List<JESSpoolFile> spoolFiles) throws IOException {
        TextTable spoolTable = new TextTable(!commandLine.hasOption("hide-table-columns"), commandLine.hasOption("format-as-csv"), delimiterCSV);

        spoolTable.addColumn("Identifier", 4);
        spoolTable.addColumn("Step", 8);
        spoolTable.addColumn("Procedure", 8);
        spoolTable.addColumn("Class", 8);
        spoolTable.addColumn("DD name", 8);
        spoolTable.addColumn("Byte count", 8);

        for (JESSpoolFile spoolFile : spoolFiles) {
            spoolTable.addRow(spoolFile.handle, spoolFile.step, spoolFile.procedure, spoolFile.type, spoolFile.nameDD,
                    spoolFile.byteCount);
        }

        return spoolTable.format();
    }

    private void processSubmit(String sourceJCL)
            throws IOException, InterruptedException {
        System.out.println("submitting job");
        JESJob jobJES = clientJES.submit(sourceJCL);
        System.out.format("job %s submitted%n", jobJES.handle);
        
        if (commandLine.hasOption("wait") || commandLine.hasOption("read-spool")) {
            System.out.format("waiting for %s job to complete%n", jobJES.handle);
            jobJES.getSpoolFiles();
            while (!jobJES.status.equals("OUTPUT")) {
                jobJES.getSpoolFiles();
                Thread.sleep(100);
            }
            
            String completion = "";
            if (jobJES.conditionCode != null) {
                completion = String.format("CC=%d", jobJES.conditionCode);
            } else if (jobJES.abendCode != null) {
                completion = String.format("ABEND=%d", jobJES.abendCode);
            }
            System.out.format("job ended with %s%n", completion);

            if (commandLine.hasOption("read-spool")) {
                System.out.format("reading '%s' (%s) job spool%n", jobJES.name, jobJES.handle);
                System.out.print(jobJES.readSpool());
            }
        }

        if (commandLine.hasOption("purge")) {
            System.out.format("purging %s job%n", jobJES.handle);
            jobJES.purge();
        }
    }

    private void processActions() throws IOException, InterruptedException {

        if (commandLine.hasOption("submit")) {
            if (commandLine.hasOption("dataset")) {
                System.out.println("executing jobs");
                
                TextTable jobTable = getJobTable();
                jobTable.addColumn("#", 4, 0);
                
                System.out.println(jobTable.formatSeparator());
                System.out.println(jobTable.formatHeader());
                System.out.println(jobTable.formatSeparator());
                
                String[] datasetNames = commandLine.getOptionValues("dataset");
                int jobIndex = 0;
                for (String datasetName : datasetNames) {
                    String sourceJCL = clientJES.retrieveFile(String.format("'%s'", datasetName));
                    
                    JESJob jobJES = clientJES.submit(sourceJCL);
                    String completion = "";
                    
                    if (commandLine.hasOption("wait")) {
                        jobJES.getSpoolFiles();
                        while (!jobJES.status.equals("OUTPUT")) {
                            jobJES.getSpoolFiles();
                            Thread.sleep(100);
                        }
                        
                        if (jobJES.conditionCode != null) {
                            completion = String.format("CC=%d", jobJES.conditionCode);
                        } else if (jobJES.abendCode != null) {
                            completion = String.format("ABEND=%d", jobJES.abendCode);
                        }
                    }
                    
                    System.out.println(jobTable.formatRow(jobIndex + 1, jobJES.name, jobJES.handle, jobJES.owner, jobJES.status, jobJES.type, completion,
                            jobJES.spoolFileCount));
                    jobIndex++;
                }
                
                System.out.println(jobTable.formatSeparator());
                
                return;
            }
            
            if (!commandLine.hasOption("filename")) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                StringBuilder builder = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                    builder.append(System.lineSeparator());
                }

                String sourceJCL = builder.toString();
                processSubmit(sourceJCL);

                return;
            }

            String[] filenames = commandLine.getOptionValues("filename");
            for (String filename : filenames) {
                String sourceJCL = new String(Files.readAllBytes(Paths.get(filename)));
                processSubmit(sourceJCL);
            }

            return;
        }

        if (commandLine.hasOption("list-jobs") || commandLine.hasOption("list-spool")
                || commandLine.hasOption("read-spool")) {

            if (commandLine.hasOption("list-jobs")) {
                System.out.println("listing jobs");
            }
            List<JESJob> jobs = clientJES.listJobs(commandLine.hasOption("detailed"));
            if (commandLine.hasOption("list-jobs")) {
                System.out.print(formatJobs(jobs));
            }

            if (commandLine.hasOption("list-spool") || commandLine.hasOption("read-spool")) {

                if (commandLine.hasOption("list-spool")) {
                    System.out.println("listing spool files");
                }
                for (JESJob jobJES : jobs) {

                    String jobTitle = jobJES.handle;
                    if (jobJES.name != null) {
                        jobTitle = String.format("'%s' (%s)", jobJES.name, jobJES.handle);
                    }

                    if (commandLine.hasOption("list-spool")) {
                        System.out.format("listing %s job spool files%n", jobTitle);
                    }
                    List<JESSpoolFile> spoolFiles = jobJES.getSpoolFiles();
                    if (commandLine.hasOption("list-spool")) {
                        System.out.print(formatSpool(spoolFiles));
                    }

                    if (commandLine.hasOption("read-spool")) {
                        for (JESSpoolFile spoolFile : spoolFiles) {
                            System.out.format("reading '%s' spool file of %s job%n", spoolFile.nameDD, jobTitle);
                            System.out.print(spoolFile.read());
                        }
                    }
                }
            }
        }
    }

    private void run(String arguments[]) throws Exception {
    	
        CommandLineParser parser = new DefaultParser();

        Options options = new Options();
        options.addOption("h", "help", false, "");

        commandLine = parser.parse(options, arguments, true);

        if (commandLine.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setOptionComparator(null);
            formatter.printHelp("jesclient", createOptions());
            return;
        }

        commandLine = parser.parse(createOptions(), arguments);

        clientJES = new JESClient();

        String hostname = null;
        if (commandLine.hasOption("hostname")) {
            hostname = commandLine.getOptionValue("hostname");
        } else {
            hostname = "localhost";
        }

        String username = null;
        if (commandLine.hasOption("username")) {
            username = commandLine.getOptionValue("username");
        } else {
            username = System.getProperty("user.name");
        }
        
        if (commandLine.hasOption("csv-delimiter")) {
        	delimiterCSV = commandLine.getOptionValue("csv-delimiter");
        } else {
        	delimiterCSV = ",";
        }

        System.out.format("connecting to '%s' host%n", hostname);
        clientJES.connect(hostname);

        System.out.format("logging in '%s' host as '%s' user%n", hostname, username);
        if (!clientJES.login(username, commandLine.getOptionValue("password"))) {
        	throw new Exception("username or password are invalid");
        }

        if (commandLine.hasOption("owner")) {
            clientJES.setOwnerFilter(commandLine.getOptionValue("owner"));
        }

        if (commandLine.hasOption("jobname")) {
            clientJES.setNameFilter(commandLine.getOptionValue("jobname"));
        }

        processActions();

        System.out.format("logging out of '%s' host%n", hostname);
        clientJES.logout();
    }

    public static void main(String arguments[]) {
        try {
            new Application().run(arguments);
        } catch (Exception error) {
            System.err.println(error.getMessage());
            System.exit(8);
        }
    }
}
