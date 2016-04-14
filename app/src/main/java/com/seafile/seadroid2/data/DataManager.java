package com.seafile.seadroid2.data;

import android.util.Log;
import android.util.Pair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.seafile.seadroid2.R;
import com.seafile.seadroid2.SeadroidApplication;
import com.seafile.seadroid2.SeafConnection;
import com.seafile.seadroid2.SeafException;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.account.AccountInfo;
import com.seafile.seadroid2.util.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DataManager {
    private static final String DEBUG_TAG = "DataManager";
    private static final long SET_PASSWORD_INTERVAL = 59 * 60 * 1000; // 59 min
    // private static final long SET_PASSWORD_INTERVAL = 5 * 1000; // 5s

    // pull to refresh
    public static final String PULL_TO_REFRESH_LAST_TIME_FOR_REPOS_FRAGMENT = "repo fragment last update";
    public static final String PULL_TO_REFRESH_LAST_TIME_FOR_STARRED_FRAGMENT = "starred fragment last update ";
    private static SimpleDateFormat ptrDataFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static Map<String, PasswordInfo> passwords = Maps.newHashMap();
    private static Map<String, Long> direntsRefreshTimeMap = Maps.newHashMap();
    public static final long REFRESH_EXPIRATION_MSECS = 10 * 60 * 1000; // 10 mins
    public static long repoRefreshTimeStamp = 0;
    
    private SeafConnection sc;
    private Account account;
    private DatabaseHelper dbHelper;
    private static final StorageManager storageManager = StorageManager.getInstance();

    private List<SeafRepo> reposCache = null;

    public DataManager(Account act) {
        account = act;
        sc = new SeafConnection(act);
        dbHelper = DatabaseHelper.getDatabaseHelper();
    }

    /**
     * Creates and returns a temporary file. It is guarantied that the file is unique and freshly
     * created. The caller has to delete that file himself.
     *
     * @return a newly created file.
     * @throws IOException if the file could not be created.
     */
    public static File createTempFile() throws IOException {
        return File.createTempFile("file-", ".tmp", storageManager.getTempDir());
    }

    /**
     * Creates and returns a temporary directory. It is guarantied that the directory is unique and
     * empty. The caller has to delete that directory himself.
     *
     * @return a newly created directory.
     * @throws IOException if the directory could not be created.
     */
    public static File createTempDir() throws IOException {
        String dirName = "dir-" + UUID.randomUUID();
        File dir = new File (storageManager.getTempDir(), dirName);
        if (dir.mkdir()) {
            return dir;
        } else {
            throw new IOException("Could not create temp directory");
        }
    }

    public String getThumbnailLink(String repoName, String repoID, String filePath, int size) {
        File file = getLocalRepoFile(repoName, repoID, filePath);

        SeafRepo seafRepo = getCachedRepoByID(repoID);
        // encrypted repo doesn\`t support thumbnails
        if (seafRepo != null && seafRepo.encrypted)
            return null;

        // use locally cached file if available
        if (file.exists()) {
            return "file://" + file.getAbsolutePath();
        } else {
            try {
                String pathEnc = URLEncoder.encode(filePath, "UTF-8");
                return account.getServer() + String.format("api2/repos/%s/thumbnail/?p=%s&size=%s", repoID, pathEnc, size);
            } catch (UnsupportedEncodingException e) {
                return null;
            }

        }
    }

    public String getThumbnailLink(String repoID, String filePath, int size) {
        SeafRepo repo = getCachedRepoByID(repoID);
        if (repo != null)
            return getThumbnailLink(repo.getName(), repoID, filePath, size);
        else
            return null;
    }

    public AccountInfo getAccountInfo() throws SeafException, JSONException {
        String json = sc.getAccountInfo();
        return parseAccountInfo(json);
    }

    private AccountInfo parseAccountInfo(String json) throws JSONException {
        JSONObject object = Utils.parseJsonObject(json);
        if (object == null)
            return null;

        return AccountInfo.fromJson(object, account.getServer());
    }

    public ServerInfo getServerInfo() throws SeafException, JSONException {
        String json = sc.getServerInfo();
        return parseServerInfo(json);
    }

    private ServerInfo parseServerInfo(String json) throws JSONException {
        JSONObject object = Utils.parseJsonObject(json);
        if (object == null)
            return null;

        return ServerInfo.fromJson(object, account.getServer());
    }

    public Account getAccount() {
        return account;
    }

    private File getFileForReposCache() {
        String filename = "repos-" + (account.server + account.email).hashCode() + ".dat";
        return new File(storageManager.getJsonCacheDir(), filename);
    }

    private File getFileForDirentCache(String dirID) {
        String filename = "dirent-" + dirID + ".dat";
        return new File(storageManager.getJsonCacheDir() + "/" + filename);
    }

    /**
     * The account directory structure of Seafile is like this:
     *
     * StorageManager.getMediaDir()
     *            |__ foo@gmail.com (cloud.seafile.com)
     *                      |__ Photos
     *                      |__ Musics
     *                      |__ ...
     *            |__ foo@mycompany.com (seafile.mycompany.com)
     *                      |__ Documents
     *                      |__ Manuals
     *                      |__ ...
     *            |__ ...
     *
     * In the above directory, the user has used two accounts.
     *
     * 1. One account has email "foo@gmail.com" and server
     * "cloud.seafile.com". Two repos, "Photos" and "Musics", has been
     * viewed.
     *
     * 2. Another account has email "foo@mycompany.com", and server
     * "seafile.mycompany.com". Two repos, "Documents" and "Manuals", has
     * been viewed.
     */
    public String getAccountDir() {
        String username = account.getEmail();
        String server = Utils.stripSlashes(account.getServerHost());
        // strip port, like :8000 in 192.168.1.116:8000
        if (server.indexOf(":") != -1)
            server = server.substring(0, server.indexOf(':'));
        String p = String.format("%s (%s)", username, server);
        p = p.replaceAll("[^\\w\\d\\.@\\(\\) ]", "_");
        String accountDir = Utils.pathJoin(storageManager.getMediaDir().getAbsolutePath(), p);
        return accountDir;
    }

    /**
     * Get the top dir of a repo. If there are multiple repos with same name,
     * say "ABC", their top dir would be "ABC", "ABC (1)", "ABC (2)", etc. The
     * mapping (repoID, dir) is stored in a database table.
     */
    private synchronized String getRepoDir(String repoName, String repoID) {
        File repoDir;

        // Check if there is a record in database
        String uniqueRepoName = dbHelper.getRepoDir(account, repoID);
        if (uniqueRepoName != null) {
            // Has record in database
            repoDir = new File(getAccountDir(), uniqueRepoName);
            if (!repoDir.exists()) {
                if (!repoDir.mkdirs()) {
                    throw new RuntimeException("Could not create library directory " + repoDir);
                }
            }
            return repoDir.getAbsolutePath();
        }

        int i = 0;
        while (true) {
            if (i == 0) {
                uniqueRepoName = repoName;
            } else {
                uniqueRepoName = repoName + " (" + i + ")";
            }
            repoDir = new File(getAccountDir(), uniqueRepoName);
            if (!repoDir.exists() && !dbHelper.repoDirExists(account, uniqueRepoName)) {
                // This repo dir does not exist yet, we can use it
                break;
            }
            i++;
        }

        if (!repoDir.mkdirs()) {
            throw new RuntimeException("Could not create repo directory " + uniqueRepoName);
        }

        // Save the new mapping in database
        dbHelper.saveRepoDirMapping(account, repoID, uniqueRepoName);

        return repoDir.getAbsolutePath();
    }

    /**
     * Each repo is placed under [account-dir]/[repo-name]. When a
     * file is downloaded, it's placed in its repo, with its full path.
     * @param repoName
     * @param repoID
     * @param path
     */
    public File getLocalRepoFile(String repoName, String repoID, String path) {
        String localPath = Utils.pathJoin(getRepoDir(repoName, repoID), path);
        File parentDir = new File(Utils.getParentPath(localPath));
        if (!parentDir.exists()) {
            // TODO should check if the directory creation succeeds
            parentDir.mkdirs();
        }

        return new File(localPath);
    }

    private List<SeafRepo> parseRepos(String json) {
        try {
            // may throw ClassCastException
            JSONArray array = Utils.parseJsonArray(json);
            if (array.length() == 0)
                return Lists.newArrayListWithCapacity(0);

            ArrayList<SeafRepo> repos = Lists.newArrayList();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SeafRepo repo = SeafRepo.fromJson(obj);
                if (repo != null)
                    repos.add(repo);
            }
            return repos;
        } catch (JSONException e) {
            Log.e(DEBUG_TAG, "parse json error");
            return null;
        } catch (Exception e) {
            // other exception, for example ClassCastException
            Log.e(DEBUG_TAG, "parseRepos exception");
            return null;
        }
    }

    public SeafRepo getCachedRepoByID(String id) {
        List<SeafRepo> cachedRepos = getReposFromCache();
        if (cachedRepos == null) {
            return null;
        }

        for (SeafRepo repo: cachedRepos) {
            if (repo.getID().equals(id)) {
                return repo;
            }
        }

        return null;
    }

    public List<SeafRepo> getReposFromCache() {
        if (reposCache != null)
            return reposCache;

        File cache = getFileForReposCache();
        if (cache.exists()) {
            String json = Utils.readFile(cache);
            if (json == null) {
                return null;
            }
            reposCache = parseRepos(json);
            return reposCache;
        }
        return null;
    }

    public List<SeafRepo> getReposFromServer() throws SeafException {
        // First decide if use cache
        if (!Utils.isNetworkOn()) {
            throw SeafException.networkException;
        }

        String json = sc.getRepos();
        //Log.d(DEBUG_TAG, "get repos from server " + json);
        if (json == null)
            return null;

        reposCache = parseRepos(json);

        try {
            File cache = getFileForReposCache();
            Utils.writeFile(cache, json);
        } catch (IOException e) {
            Log.e(DEBUG_TAG, "Could not write repo cache to disk.", e);
        }

        return reposCache;
    }

    private void saveDirentContent(String repoID, String parentDir, String dirID, String content) {
        deleteOldDirentContent(repoID, parentDir);
        dbHelper.saveDirents(repoID, parentDir, dirID);

        try {
            File cache = getFileForDirentCache(dirID);
            Utils.writeFile(cache, content);
        } catch (IOException e) {
            Log.e(DEBUG_TAG, "Could not write dirent cache to disk.", e);
        }
    }

    /**
     * Clean up old dirent cache for a directory where we have received new data.
     *
     * @param repoID
     * @param dir
     */
    private void deleteOldDirentContent(String repoID, String dir) {
        String dirID = dbHelper.getCachedDirents(repoID, dir);

        // identical directory content results in same dirID. So check if whether
        // the dirID is referenced multiple times before deleting it.
        if (dirID != null && dbHelper.getCachedDirentUsage(dirID) <= 1) {
            File file = getFileForDirentCache(dirID);
            file.delete();
        }
        // and finally delete the entry in the SQL table
        dbHelper.removeCachedDirents(repoID, dir);
    }

    public synchronized File getFile(String repoName, String repoID, String path,
                        ProgressMonitor monitor) throws SeafException {

        String cachedFileID = null;
        SeafCachedFile cf = getCachedFile(repoName, repoID, path);
        File localFile = getLocalRepoFile(repoName, repoID, path);
        // If local file is up to date, show it
        if (cf != null) {
            if (localFile.exists()) {
                cachedFileID = cf.fileID;
            }
        }

        Pair<String, File> ret = sc.getFile(repoID, path, localFile.getPath(), cachedFileID, monitor);

        String fileID = ret.first;
        if (fileID.equals(cachedFileID)) {
            // cache is valid
            return localFile;
        } else {
            File file = ret.second;
            addCachedFile(repoName, repoID, path, fileID, file);
            return file;
        }
    }

    private List<SeafDirent> parseDirents(String json) {
        try {
            JSONArray array = Utils.parseJsonArray(json);
            if (array == null)
                return null;

            ArrayList<SeafDirent> dirents = Lists.newArrayList();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SeafDirent de = SeafDirent.fromJson(obj);
                if (de != null)
                    dirents.add(de);
            }
            return dirents;
        } catch (JSONException e) {
            Log.e(DEBUG_TAG, "Could not parse cached dirent", e);
            return null;
        }
    }

    private List<SeafStarredFile> parseStarredFiles(String json) {
        try {
            JSONArray array = Utils.parseJsonArray(json);
            if (array == null)
                return null;

            ArrayList<SeafStarredFile> starredFiles = Lists.newArrayList();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SeafStarredFile sf = SeafStarredFile.fromJson(obj);
                if (sf != null)
                    starredFiles.add(sf);
            }
            return starredFiles;
        } catch (JSONException e) {
            Log.e(DEBUG_TAG, "Could not parse cached starred files", e);
            return null;
        }
    }

    public List<SeafDirent> getCachedDirents(String repoID, String path) {
        String dirID = dbHelper.getCachedDirents(repoID, path);
        if (dirID == null) {
            return null;
        }

        File cache = getFileForDirentCache(dirID);
        if (!cache.exists()) {
            return null;
        }

        String json = Utils.readFile(cache);
        if (json == null) {
            return null;
        }

        return parseDirents(json);
    }

    /**
     * In four cases we need to visit the server for dirents
     *
     * 1. No cached dirents
     * 2. User clicks "refresh" button.
     * 3. Download all dirents within a folder
     * 4. View starred or searched files in gallery without available local cache
     *
     * In the second case, the local cache may still be valid.
     */
    public List<SeafDirent> getDirentsFromServer(String repoID, String path) throws SeafException {

        // first fetch our cached dirent and read it
        String cachedDirID = dbHelper.getCachedDirents(repoID, path);
        String cachedContent = null;
        File cacheFile = getFileForDirentCache(cachedDirID);
        if (cacheFile.exists()) {
            cachedContent = Utils.readFile(cacheFile);
        }

        // if that didn't work, then we have no cache.
        if (cachedContent == null) {
            cachedDirID = null;
        }

        // fetch new dirents. ret.second will be null if the cache is still valid
        Pair<String, String> ret = sc.getDirents(repoID, path, cachedDirID);

        String content;
        if (ret.second != null) {
            String dirID = ret.first;
            content = ret.second;
            saveDirentContent(repoID, path, dirID, content);
        } else {
            content = cachedContent;
        }

        return parseDirents(content);
    }

    public List<SeafStarredFile> getStarredFiles() throws SeafException {
        String starredFiles = sc.getStarredFiles();
        Log.v(DEBUG_TAG, "Save starred files: " + starredFiles);
        if (starredFiles == null) {
            return null;
        }
        dbHelper.saveCachedStarredFiles(account, starredFiles);
        return parseStarredFiles(starredFiles);
    }

    public List<SeafStarredFile> getCachedStarredFiles() {
        String starredFiles = dbHelper.getCachedStarredFiles(account);
        Log.v(DEBUG_TAG, "Get cached starred files: " + starredFiles);
        if (starredFiles == null) {
            return null;
        }
        return parseStarredFiles(starredFiles);
    }


    public SeafCachedFile getCachedFile(String repoName, String repoID, String path) {
        SeafCachedFile cf = dbHelper.getFileCacheItem(repoID, path, this);
        return cf;
    }
    
    public List<SeafCachedFile> getCachedFiles() {
        return dbHelper.getFileCacheItems(this);
    }

    public void addCachedFile(String repoName, String repoID, String path, String fileID, File file) {
        // notify Android Gallery that a new file has appeared

        // file does not always reside in Seadroid directory structure (e.g. camera upload)
        if (file.exists())
            storageManager.notifyAndroidGalleryFileChange(file);

        SeafCachedFile item = new SeafCachedFile();
        item.repoName = repoName;
        item.repoID = repoID;
        item.path = path;
        item.fileID = fileID;
        item.accountSignature = account.getSignature();
        dbHelper.saveFileCacheItem(item, this);
    }

    public void removeCachedFile(SeafCachedFile cf) {
        // TODO should check if the file deletion succeeds
        cf.file.delete();
        dbHelper.deleteFileCacheItem(cf);
    }
    
    public void setPassword(String repoID, String passwd) throws SeafException {
        sc.setPassword(repoID, passwd);
    }

    public void uploadFile(String repoName, String repoID, String dir, String filePath,
            ProgressMonitor monitor, boolean isCopyToLocal) throws SeafException {
        uploadFileCommon(repoName, repoID, dir, filePath, monitor, false, isCopyToLocal);
    }

    public void updateFile(String repoName, String repoID, String dir, String filePath,
            ProgressMonitor monitor, boolean isCopyToLocal) throws SeafException {
        uploadFileCommon(repoName, repoID, dir, filePath, monitor, true, isCopyToLocal);
    }

    private void uploadFileCommon(String repoName, String repoID, String dir,
                                  String filePath, ProgressMonitor monitor,
                                  boolean isUpdate, boolean isCopyToLocal) throws SeafException {
        String newFileID = null;
        if (isUpdate) {
            newFileID  = sc.updateFile(repoID, dir, filePath, monitor);
        } else {
            newFileID  = sc.uploadFile(repoID, dir, filePath, monitor);
        }

        if (newFileID == null || newFileID.length() == 0) {
            return;
        }

        File srcFile = new File(filePath);
        String path = Utils.pathJoin(dir, srcFile.getName());
        File fileInRepo = getLocalRepoFile(repoName, repoID, path);

        if (isCopyToLocal) {
            if (!isUpdate) {
                // Copy the uploaded file to local repo cache
                try {
                    Utils.copyFile(srcFile, fileInRepo);
                } catch (IOException e) {
                    return;
                }
            }
        }
        // Update file cache entry
        addCachedFile(repoName, repoID, path, newFileID, fileInRepo);
    }

    public void createNewDir(String repoID, String parentDir, String dirName) throws SeafException {
        Pair<String, String> ret = sc.createNewDir(repoID, parentDir, dirName);
        if (ret == null) {
            return;
        }

        String newDirID = ret.first;
        String response = ret.second;

        // The response is the dirents of the parentDir after creating
        // the new dir. We save it to avoid request it again
        saveDirentContent(repoID, parentDir, newDirID, response);
    }

    public void createNewFile(String repoID, String parentDir, String fileName) throws SeafException {
        Pair<String, String> ret = sc.createNewFile(repoID, parentDir, fileName);
        if (ret == null) {
            return;
        }

        String newDirID = ret.first;
        String response = ret.second;

        // The response is the dirents of the parentDir after creating
        // the new file. We save it to avoid request it again
        saveDirentContent(repoID, parentDir, newDirID, response);
    }

    public File getLocalCachedFile(String repoName, String repoID, String filePath, String fileID) {
        File localFile = getLocalRepoFile(repoName, repoID, filePath);
        if (!localFile.exists()) {
            return null;
        }

        if (!Utils.isNetworkOn()) {
            return localFile;
        }

        SeafCachedFile cf = getCachedFile(repoName, repoID, filePath);
        if (cf != null && cf.fileID != null && cf.fileID.equals(fileID)) {
            return localFile;
        } else {
            return null;
        }
    }

    public void star(String repoID, String path) throws SeafException {
        sc.star(repoID, path);
    }

    public void unstar(String repoID, String path) throws SeafException {
        sc.unstar(repoID, path);
    }

    public void rename(String repoID, String path, String newName, boolean isdir) throws SeafException {
        Pair<String, String> ret = sc.rename(repoID, path, newName, isdir);
        if (ret == null) {
            return;
        }

        String newDirID = ret.first;
        String response = ret.second;

        // The response is the dirents of the parentDir after renaming the
        // file/folder. We save it to avoid request it again.
        saveDirentContent(repoID, Utils.getParentPath(path), newDirID, response);

        // TODO: delete or rename cached files, dirent cache, etc.
        /*
         * I think it is more simple and easier if we provide a "clear cache" button in Settings,
         * just like what we have done with thumbnail caches.
         * And hopefully it already exist.
         * Users can manually clear them if they are boring with those temp files.
         */
    }

    public void delete(String repoID, String path, boolean isdir) throws SeafException{
        Pair<String, String> ret = sc.delete(repoID, path, isdir);
        if (ret == null){
            return;
        }

        String newDirID = ret.first;
        String response = ret.second;

        // The response is the dirents of the parentDir after deleting the
        // file/folder. We save it to avoid request it again
        saveDirentContent(repoID, Utils.getParentPath(path), newDirID, response);

        // TODO: isdir==true: recursively delete cached files, dirent cache, etc.
        /*
         * I think it is more simple and easier if we provide a "clear cache" button in Settings,
         * just like what we have done with thumbnail caches.
         * And hopefully it already exist.
         * Users can manually clear them if they are boring with those temp files.
         */

    }

    public void copy(String srcRepoId, String srcDir, String srcFn,
                     String dstRepoId, String dstDir) throws SeafException {
        sc.copy(srcRepoId, srcDir, srcFn, dstRepoId, dstDir);
        
        // After copying, we need to refresh the destination list
        getDirentsFromServer(dstRepoId, dstDir);
    }

    public void move(String srcRepoId, String srcDir, String srcFn, String dstRepoId, String dstDir,
                     boolean batch) throws SeafException {
        Pair<String, String> ret = null;
        if (batch) {
            sc.move(srcRepoId, srcDir, srcFn, dstRepoId, dstDir);
        } else {
            String srcPath = Utils.pathJoin(srcDir, srcFn);
            ret = sc.move(srcRepoId, srcPath, dstRepoId, dstDir);
        }

        // After moving, we need to refresh the destination list
        getDirentsFromServer(dstRepoId, dstDir);

        // We also need to refresh the original list
        getDirentsFromServer(srcRepoId, srcDir);

        if (ret == null) {
            return;
        }

        String newDirID = ret.first;
        String response = ret.second;

        // The response is the list of dst after moving the
        // file/folder. We save it to avoid request it again
        saveDirentContent(dstRepoId, dstDir, newDirID, response);

    }

    public SeafActivities getEvents(int start) throws SeafException, JSONException {
        if (!Utils.isNetworkOn()) {
            throw SeafException.networkException;
        }

        final String json = sc.getEvents(start);

        if (json == null) return null;

        final JSONObject object = Utils.parseJsonObject(json);
        final int moreOffset = object.getInt("more_offset");
        final boolean more = object.getBoolean("more");
        final List<SeafEvent> events = parseEvents(json);
        return new SeafActivities(events, moreOffset, more);

    }

    public String getHistoryChanges(String repoId, String commitId) throws SeafException {
        return sc.getHistoryChanges(repoId, commitId);
    }

    public List<SeafEvent> parseEvents(String json) {
        try {
            // may throw ClassCastException
            JSONArray array = Utils.parseJsonArrayByKey(json, "events");
            if (array.length() == 0)
                return Lists.newArrayListWithCapacity(0);

            ArrayList<SeafEvent> events = Lists.newArrayList();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SeafEvent event = SeafEvent.fromJson(obj);
                if (event != null)
                    events.add(event);
            }
            return events;
        } catch (JSONException e) {
            Log.e(DEBUG_TAG, "parse json error");
            return null;
        } catch (Exception e) {
            // other exception, for example ClassCastException
            Log.e(DEBUG_TAG, "parseEvents exception");
            return null;
        }
    }

    private static class PasswordInfo {
        String password;
        long timestamp;

        public PasswordInfo(String password, long timestamp) {
            this.password = password;
            this.timestamp = timestamp;
        }
    }

    public static boolean getRepoPasswordSet(String repoID) {
        PasswordInfo info = passwords.get(repoID);
        if (info == null) {
            return false;
        }

        if (Utils.now() - info.timestamp > SET_PASSWORD_INTERVAL) {
            return false;
        }

        return true;
    }

    public static void setRepoPasswordSet(String repoID, String password) {
        passwords.put(repoID, new PasswordInfo(password, Utils.now()));
    }

    public static String getRepoPassword(String repoID) {
        PasswordInfo info = passwords.get(repoID);
        if (info == null) {
            return null;
        }

        return info.password;
    }

    /**
     * calculate if refresh time is expired, the expiration is 10 mins 
     */
    public boolean isReposRefreshTimeout() {
        if (Utils.now() < repoRefreshTimeStamp + REFRESH_EXPIRATION_MSECS) {
            return false;
        }

        return true;
    }

    public boolean isDirentsRefreshTimeout(String repoID, String path) {
        if (!direntsRefreshTimeMap.containsKey(Utils.pathJoin(repoID, path))) {
            return true;
        }
        long lastRefreshTime = direntsRefreshTimeMap.get(Utils.pathJoin(repoID, path));

        if (Utils.now() < lastRefreshTime + REFRESH_EXPIRATION_MSECS) {
            return false;
        }
        return true;
    }

    public boolean isStarredFilesRefreshTimeout() {
        if (!direntsRefreshTimeMap.containsKey(PULL_TO_REFRESH_LAST_TIME_FOR_STARRED_FRAGMENT)) {
            return true;
        }
        long lastRefreshTime = direntsRefreshTimeMap.get(PULL_TO_REFRESH_LAST_TIME_FOR_STARRED_FRAGMENT);

        if (Utils.now() < lastRefreshTime + REFRESH_EXPIRATION_MSECS) {
            return false;
        }
        return true;
    }

    public void setDirsRefreshTimeStamp(String repoID, String path) {
        direntsRefreshTimeMap.put(Utils.pathJoin(repoID, path), Utils.now());
    }
    
    public void setReposRefreshTimeStamp() {
        repoRefreshTimeStamp = Utils.now();
    }

    public void saveLastPullToRefreshTime(long lastUpdateTime, String whichFragment) {
        direntsRefreshTimeMap.put(whichFragment, lastUpdateTime);
    }

    public String getLastPullToRefreshTime(String whichFragment) {

        if (!direntsRefreshTimeMap.containsKey(whichFragment)) {
            return null;
        }

        Long objLastUpdate = direntsRefreshTimeMap.get(whichFragment);
        if (objLastUpdate == null) return null;

        long lastUpdate = direntsRefreshTimeMap.get(whichFragment);

        long diffTime = new Date().getTime() - lastUpdate;
        int seconds = (int) (diffTime / 1000);
        if (diffTime < 0) {
            return null;
        }
        if (seconds <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SeadroidApplication.getAppContext().getString(R.string.pull_to_refresh_last_update));

        if (seconds < 60) {
            sb.append(SeadroidApplication.getAppContext().getString(R.string.pull_to_refresh_last_update_seconds_ago, seconds));
        } else {
            int minutes = (seconds / 60);
            if (minutes > 60) {
                int hours = minutes / 60;
                if (hours > 24) {
                    Date date = new Date(lastUpdate);
                    sb.append(ptrDataFormat.format(date));
                } else {
                    sb.append(SeadroidApplication.getAppContext().getString(R.string.pull_to_refresh_last_update_hours_ago, hours));
                }

            } else {
                sb.append(SeadroidApplication.getAppContext().getString(R.string.pull_to_refresh_last_update_minutes_ago, minutes));
            }
        }
        return sb.toString();
    }

    /**
     * search on server
     *
     * @param query query text
     * @param page pass 0 to disable page loading
     *
     * @return json format strings of searched result
     *
     * @throws SeafException
     */
    public String search(String query, int page) throws SeafException {
        String json = sc.searchLibraries(query, page);
        return json;
    }

    public ArrayList<SearchedFile> parseSearchResult(String json) {
        if (json == null)
            return null;

        try {
            JSONArray array = Utils.parseJsonArrayByKey(json, "results");
            if (array == null)
                return null;

            ArrayList<SearchedFile> searchedFiles = Lists.newArrayList();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SearchedFile sf = SearchedFile.fromJson(obj);
                if (sf != null)
                    searchedFiles.add(sf);
            }
            return searchedFiles;
        } catch (JSONException e) {
            return null;
        }
    }
}