package org.jenkinsci.plugins.gitclient;

import au.com.bytecode.opencsv.CSVReader;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.io.Files;
import hudson.plugins.git.GitException;
import hudson.util.LogTaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import static junit.framework.TestCase.assertTrue;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

/**
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class CredentialsTest {

    // Required for credentials use
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private final String gitImpl;
    private final String gitRepoURL;
    private final String username;
    private final String password;
    private final File privateKey;

    private GitClient git;
    private File repo;
    private BasicSSHUserPrivateKey credential;

    private List<String> expectedLogSubstrings = new ArrayList<String>();

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    private int logCount;
    private LogHandler handler;
    private LogTaskListener listener;
    private static final String LOGGING_STARTED = "*** Logging started ***";

    private final static File homeDir = new File(System.getProperty("user.home"));
    private final static File sshDir = new File(homeDir, ".ssh");
    private final static File defaultPrivateKey = new File(sshDir, "id_rsa");

    /* Directory containing local private keys for tests */
    private final static File authDataDir = new File(sshDir, "auth-data");

    private final static File currDir = new File(".");

    private static PrintStream log() {
        return StreamTaskListener.fromStdout().getLogger();
    }

    public CredentialsTest(String gitImpl, String gitRepoUrl, String username, String password, File privateKey) {
        this.gitImpl = gitImpl;
        this.gitRepoURL = gitRepoUrl;
        if (privateKey == null && defaultPrivateKey.exists()) {
            privateKey = defaultPrivateKey;
        }
        this.privateKey = privateKey;
        this.username = username;
        this.password = password;
        log().println("Repo: " + gitRepoUrl);
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        repo = temporaryDirectoryAllocator.allocate();
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
        git = Git.with(listener, new hudson.EnvVars()).in(repo).using(gitImpl).getClient();
        if (gitImpl.equals("git")) {
            addExpectedLogSubstring("> git -c core.askpass=true fetch ");
            addExpectedLogSubstring("> git checkout -b master ");
        }
        /* FetchWithCredentials does not log expected message */
        /*
         if (gitImpl.equals("jgit")) {
         addExpectedLogSubstring("remote: Counting objects");
         }
         */
    }

    @After
    public void tearDown() {
        git.clearCredentials();
        temporaryDirectoryAllocator.disposeAsync();
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            for (String expectedLogSubstring : expectedLogSubstrings) {
                assertTrue("No '" + expectedLogSubstring + "' in " + messages,
                        handler.containsMessageSubstring(expectedLogSubstring));
            }
        } finally {
            clearExpectedLogSubstring();
            handler.close();
        }
    }

    protected void addExpectedLogSubstring(String expectedLogSubstring) {
        this.expectedLogSubstrings.add(expectedLogSubstring);
    }

    protected void clearExpectedLogSubstring() {
        this.expectedLogSubstrings = new ArrayList<String>();
    }

    private BasicSSHUserPrivateKey newPrivateKeyCredential(String username, File privateKey) throws IOException {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "private-key-" + privateKey.getPath();
        String privateKeyData = Files.toString(privateKey, Charset.forName("UTF-8"));
        BasicSSHUserPrivateKey.PrivateKeySource privateKeySource = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKeyData);
        String passphrase = null;
        String description = "private key from " + privateKey.getPath();
        return new BasicSSHUserPrivateKey(scope, id, username, privateKeySource, passphrase, description);
    }

    private StandardUsernamePasswordCredentials newUsernamePasswordCredential(String username, String password) {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "username-" + username + "-password-" + password;
        StandardUsernamePasswordCredentials usernamePasswordCredential = new UsernamePasswordCredentialsImpl(scope, id, "desc: " + id, username, password);
        return usernamePasswordCredential;
    }

    private static boolean isCredentialsSupported() throws IOException, InterruptedException {
        CliGitAPIImpl cli = (CliGitAPIImpl) Git.with(null, new hudson.EnvVars()).in(currDir).using("git").getClient();
        return cli.isAtLeastVersion(1, 7, 9, 0);
    }

    @Parameterized.Parameters(name = "{2}-{1}-{0}")
    public static Collection gitRepoUrls() throws MalformedURLException, FileNotFoundException, IOException, InterruptedException {
        List<Object[]> repos = new ArrayList<Object[]>();
        String[] implementations = isCredentialsSupported() ? new String[]{"git", "jgit"} : new String[]{"jgit"};
        for (String implementation : implementations) {
            /* Add master repository as authentication test with private
             * key of current user.  Try to test at least one
             * authentication case, even if there is no repos.csv file in
             * the external directory.
             */
            if (defaultPrivateKey.exists()) {
                String username = System.getProperty("user.name");
                String url = "https://github.com/jenkinsci/git-client-plugin.git";
                Object[] masterRepo = {implementation, url, username, null, defaultPrivateKey};
                repos.add(masterRepo);
            }

            /* Add additional repositories if the ~/.ssh/auth-data directory
             * contains a repos.csv file defining the repositories to test and the
             * private key files to use for those tests.
             */
            File authDataDefinitions = new File(authDataDir, "repos.csv");
            if (authDataDefinitions.exists()) {
                CSVReader reader = new CSVReader(new FileReader(authDataDefinitions));
                List<String[]> myEntries = reader.readAll();
                for (String[] entry : myEntries) {
                    if (entry.length < 3) {
                        System.out.println("Too few fields(" + entry.length + ") in " + entry[0]);
                        continue;
                    }
                    String repoURL = entry[0];
                    String username = entry[1];
                    File privateKey = new File(authDataDir, entry[2]);
                    if (!privateKey.exists()) {
                        privateKey = null;
                    }
                    String password = null;
                    if (entry.length > 3) {
                        password = entry[3];
                    }
                    Object[] repo = {implementation, repoURL, username, password, privateKey};
                    repos.add(repo);
                }
            }
        }
        Collections.shuffle(repos); // randomize test order
        int toIndex = repos.size() < 3 ? repos.size() : 3;
        if (TEST_ALL_CREDENTIALS) {
            toIndex = repos.size();
        }
        return repos.subList(0, toIndex);
    }

    private String listDir(File dir) {
        File[] files = repo.listFiles();
        StringBuilder fileList = new StringBuilder();
        for (File file : files) {
            fileList.append(file.getName());
            fileList.append(',');
        }
        fileList.deleteCharAt(fileList.length() - 1);
        return fileList.toString();
    }

    @Test
    public void testFetchWithCredentials() throws URISyntaxException, GitException, InterruptedException, MalformedURLException, IOException {
        File readme = new File(repo, "README.md");
        String origin = "origin";
        List<RefSpec> refSpecs = new ArrayList<RefSpec>();
        refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/" + origin + "/*"));
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse("readme in " + repo + ", has " + listDir(repo), readme.exists());
        if (password != null) {
            git.addDefaultCredentials(newUsernamePasswordCredential(username, password));
        } else {
            git.addDefaultCredentials(newPrivateKeyCredential(username, privateKey));
        }
        git.fetch_().from(new URIish(gitRepoURL), refSpecs).execute();
        git.setRemoteUrl(origin, gitRepoURL);
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        log().println("Checking out " + master + " from " + gitRepoURL);
        git.checkout().branch("master").ref(master.getName()).deleteBranchIfExist(true).execute();
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().getRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        assertTrue("No readme in " + repo + ", has " + listDir(repo), readme.exists());
    }

    @Test
    public void testCloneWithCredentials() throws URISyntaxException, GitException, InterruptedException, MalformedURLException, IOException {
        File readme = new File(repo, "README.md");
        String origin = "origin";
        if (password != null) {
            git.addDefaultCredentials(newUsernamePasswordCredential(username, password));
        } else {
            git.addDefaultCredentials(newPrivateKeyCredential(username, privateKey));
        }
        CloneCommand cmd = git.clone_().url(gitRepoURL).repositoryName(origin);
        if (gitImpl.equals("git")) {
            // Reduce network transfer by using a local reference repository
            // JGit does not support reference repositories
            cmd.reference(currDir.getAbsolutePath());
        }
        cmd.execute();
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        log().println("Checking out " + master + " from " + gitRepoURL);
        git.checkout().branch("master").ref(origin + "/master").deleteBranchIfExist(true).execute();
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().getRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        assertTrue("No readme in " + repo + ", has " + listDir(repo), readme.exists());
    }

    private static final boolean TEST_ALL_CREDENTIALS = Boolean.valueOf(System.getProperty("TEST_ALL_CREDENTIALS", "false"));
}
