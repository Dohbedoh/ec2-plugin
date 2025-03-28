/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2.ssh;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.session.ClientSession;

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2SSHLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2UnixLauncher.class.getName());

    @Override
    protected void preInstalls(
            @NonNull EC2Computer computer,
            @NonNull ClientSession clientSession,
            @NonNull String javaPath,
            @NonNull PrintStream logger,
            @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        executeRemote(
                computer,
                clientSession,
                javaPath + " -fullversion",
                "sudo amazon-linux-extras install java-openjdk11 -y; sudo yum install -y fontconfig java-11-openjdk",
                logger,
                listener);
        executeRemote(computer, clientSession, "which scp", "sudo yum install -y openssh-clients", logger, listener);
    }

    @Override
    protected void createDir(@NonNull ClientSession clientSession, @NonNull String dir, @NonNull PrintStream logger)
            throws IOException, InterruptedException {
        executeRemote(clientSession, "mkdir -p " + dir, logger);
    }

    @Override
    protected boolean fileExist(@NonNull ClientSession clientSession, @NonNull String file, @NonNull PrintStream logger)
            throws IOException, InterruptedException {
        return executeRemote(clientSession, "test -e " + file, logger);
    }

    @Override
    protected void launchWithSshCommand(
            @NonNull EC2Computer computer,
            @NonNull SlaveTemplate template,
            @NonNull EC2AbstractSlave node,
            @NonNull TaskListener listener,
            @NonNull String launchString)
            throws IOException, InterruptedException {
        File identityKeyFile = createIdentityKeyFile(computer);
        String ec2HostAddress = getEC2HostAddress(computer, template);
        File hostKeyFile = createHostKeyFile(computer, ec2HostAddress, listener);
        String userKnownHostsFileFlag = "";
        if (hostKeyFile != null) {
            userKnownHostsFileFlag = String.format(" -o \"UserKnownHostsFile=%s\"", hostKeyFile.getAbsolutePath());
        }

        try {
            // Obviously the controller must have an installed ssh client.
            // Depending on the strategy selected on the UI, we set the StrictHostKeyChecking flag
            String sshClientLaunchString = String.format(
                    "ssh -o StrictHostKeyChecking=%s%s%s -i %s %s@%s -p %d %s",
                    template.getHostKeyVerificationStrategy().getSshCommandEquivalentFlag(),
                    userKnownHostsFileFlag,
                    getEC2HostKeyAlgorithmFlag(computer),
                    identityKeyFile.getAbsolutePath(),
                    node.remoteAdmin,
                    ec2HostAddress,
                    node.getSshPort(),
                    launchString);

            logInfo(computer, listener, "Launching remoting agent (via SSH client process): " + sshClientLaunchString);
            CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString, null);
            commandLauncher.launch(computer, listener);
        } finally {
            if (!identityKeyFile.delete()) {
                LOGGER.log(Level.WARNING, "Failed to delete identity key file");
            }
            if (hostKeyFile != null && !hostKeyFile.delete()) {
                LOGGER.log(Level.WARNING, "Failed to delete host key file");
            }
        }
    }

    @Override
    protected String getDefaultTmpDir() {
        return "/tmp";
    }

    @Override
    protected String getInitFileName() {
        return "init.sh";
    }

    @Override
    protected String getInitMarkerFilePath() {
        return "~/.hudson-run-init";
    }

    @Override
    protected String touchFileCommand(@NonNull String filePath) {
        return "touch " + filePath;
    }
}
