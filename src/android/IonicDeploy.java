package io.ionic.deploy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.util.regex.Matcher;
import java.net.URL;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;


class JsonHttpResponse {
  String message;
  Boolean error;
  JSONObject json;
}

public class IonicDeploy extends CordovaPlugin {
  String server = "https://api.ionic.io";
  Context myContext = null;
  String app_id = null;
  boolean debug = true;
  SharedPreferences prefs = null;
  CordovaWebView v = null;
  String version_label = null;
  boolean ignore_deploy = false;
  JSONObject last_update;

  public static final String INDEX_UPDATED = "INDEX_UPDATED";
  public static final String NO_DEPLOY_LABEL = "NO_DEPLOY_LABEL";
  public static final String NO_DEPLOY_AVAILABLE = "NO_DEPLOY_AVAILABLE";
  public static final String NOTHING_TO_IGNORE = "NOTHING_TO_IGNORE";
  public static final int VERSION_AHEAD = 1;
  public static final int VERSION_MATCH = 0;
  public static final int VERSION_BEHIND = -1;

  /**
   * Returns the data contained at filePath as a string
   *
   * @param filePath the URL of the file to read
   * @return the string contents of filePath
   **/
  private static String getStringFromFile (String filePath) throws Exception {
    // Grab the file and init vars
    URI uri = URI.create(filePath);
    File file = new File(uri);
    StringBuilder text = new StringBuilder();
    BufferedReader br = new BufferedReader(new FileReader(file));
    String line;

    //Read text from file
    while ((line = br.readLine()) != null) {
      text.append(line);
      text.append('\n');
    }
    br.close();

    return text.toString();
  }

  /**
   * Sets the context of the Command. This can then be used to do things like
   * get file paths associated with the Activity.
   *
   * @param cordova The context of the main Activity.
   * @param cWebView The CordovaWebView Cordova is running in.
   */
  public void initialize(CordovaInterface cordova, CordovaWebView cWebView) {
    super.initialize(cordova, cWebView);
    this.myContext = this.cordova.getActivity().getApplicationContext();
    this.prefs = getPreferences();
    this.v = webView;
    this.version_label = prefs.getString("ionicdeploy_version_label", IonicDeploy.NO_DEPLOY_LABEL);

  }

  private String getUUID() {
    return this.prefs.getString("uuid", IonicDeploy.NO_DEPLOY_AVAILABLE);
  }

  private String getUUID(String defaultUUID) {
    return this.prefs.getString("uuid", defaultUUID);
  }

  private PackageInfo getAppPackageInfo() throws NameNotFoundException {
    PackageManager packageManager = this.cordova.getActivity().getPackageManager();
    PackageInfo packageInfo = packageManager.getPackageInfo(this.cordova.getActivity().getPackageName(), 0);
    return packageInfo;
  }

  private void initVersionChecks() {
    String ionicdeploy_version_label = IonicDeploy.NO_DEPLOY_LABEL;
    String uuid = this.getUUID();

    try {
      ionicdeploy_version_label = this.constructVersionLabel(this.getAppPackageInfo(), uuid);
    } catch (NameNotFoundException e) {
      logMessage("INIT", "Could not get package info");
    }

    if(!ionicdeploy_version_label.equals(IonicDeploy.NO_DEPLOY_LABEL)) {
      if(this.debug) {
        logMessage("INIT", "Version Label 1: " + this.version_label);
        logMessage("INIT", "Version Label 2: " + ionicdeploy_version_label);
      }
      if(!this.version_label.equals(ionicdeploy_version_label)) {
        this.ignore_deploy = true;
        this.updateVersionLabel(uuid);
        this.prefs.edit().remove("uuid").apply();
      }
    }
  }

  private String constructVersionLabel(PackageInfo packageInfo, String uuid) {
    String version = packageInfo.versionName;
    String timestamp = String.valueOf(packageInfo.lastUpdateTime);
    return version + ":" + timestamp + ":" + uuid;
  }

  private String[] deconstructVersionLabel(String label) {
    return label.split(":");
  }

  public Object onMessage(String id, Object data) {
    boolean is_nothing = "file:///".equals(String.valueOf(data));
    boolean is_index = "file:///android_asset/www/index.html".equals(String.valueOf(data));
    boolean is_original = (is_nothing || is_index) ? true : false;

    if("onPageStarted".equals(id) && is_original) {
      final String uuid = this.getUUID();

      if(!IonicDeploy.NO_DEPLOY_AVAILABLE.equals(uuid)) {
        logMessage("LOAD", "Init Deploy Version");
        this.redirect(uuid);
      }
    }
    return null;
  }

  /**
   * Executes the request and returns PluginResult.
   *
   * @param action            The action to execute.
   * @param args              JSONArry of arguments for the plugin.
   * @param callbackContext   The callback id used when calling back into JavaScript.
   * @return                  True if the action was valid, false if not.
   */
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

    this.app_id = args.getString(0);
    this.prefs = getPreferences();

    initApp(args.getString(0));

    final SharedPreferences prefs = this.prefs;

    if (action.equals("initialize")) {
      this.server = args.getString(1);
      callbackContext.success("true");
      return true;
    } else if (action.equals("check")) {
      logMessage("CHECK", "Checking for updates");
      final String channel_tag = args.getString(1);
      cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          checkForUpdates(callbackContext, channel_tag);
        }
      });
      return true;
    } else if (action.equals("download")) {
      logMessage("DOWNLOAD", "Downloading updates");
      cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          downloadUpdate(callbackContext);
        }
      });
      return true;
    } else if (action.equals("extract")) {
      logMessage("EXTRACT", "Extracting update");
      final String uuid = args.getString(0);
      cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          unzip("www.zip", uuid, callbackContext);
        }
      });
      this.prefs.edit().putString("uuid", uuid).apply();
      return true;
    } else if (action.equals("redirect")) {
      final String uuid = this.getUUID("");
      this.redirect(uuid);
      return true;
    } else if (action.equals("info")) {
      this.info(callbackContext);
      return true;
    } else if (action.equals("getVersions")) {
      callbackContext.success(this.getDeployVersions());
      return true;
    } else if (action.equals("deleteVersion")) {
      final String uuid = args.getString(1);
      boolean status = this.removeVersion(uuid);
      if (status) {
        callbackContext.success();
      } else {
        callbackContext.error("Error attempting to remove the version, are you sure it exists?");
      }
      return true;
    } else if (action.equals("getMetadata")) {
      String uuid = null;
      try {
        uuid = args.getString(1);
      } catch (JSONException e) {
        uuid = this.prefs.getString("upstream_uuid", "");
      }

      if (uuid.equals("null")) {
        uuid = this.prefs.getString("upstream_uuid", "");
      }

      if(uuid == null || uuid.equals("")) {
        callbackContext.error("NO_DEPLOY_UUID_AVAILABLE");
      } else {
        final String metadata_uuid = uuid;
        cordova.getThreadPool().execute(new Runnable() {
          public void run() {
            getMetadata(callbackContext, metadata_uuid);
          }
        });
      }
      return true;
    } else if (action.equals("parseUpdate")) {
      logMessage("PARSEUPDATE", "Checking response for updates");
      final String response = args.getString(1);
      cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          parseUpdate(callbackContext, response);
        }
      });
      return true;
    } else {
      return false;
    }
  }

  private JSONObject getMetadata(CallbackContext callbackContext, final String uuid) {
    String strictuuid = uuid.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
    String endpoint = "/deploy/snapshots/" + strictuuid + "?app_id=" + this.app_id;
    JsonHttpResponse response = new JsonHttpResponse();
    JSONObject json = new JSONObject();
    HttpURLConnection urlConnection = null;

    String result = "{}";
    try {
      URL url = new URL(this.server + endpoint);
      HttpURLConnection.setFollowRedirects(true);
      urlConnection = (HttpURLConnection) url.openConnection();
      InputStream in = new BufferedInputStream(urlConnection.getInputStream());
      result = readStream(in);
    } catch (MalformedURLException e) {
      callbackContext.error("DEPLOY_HTTP_ERROR");
      response.error = true;
    } catch (IOException e) {
      callbackContext.error("DEPLOY_HTTP_ERROR");
      response.error = true;
    }

    if (urlConnection != null) {
      urlConnection.disconnect();
    }

    JSONObject jsonResponse = null;
    try {
      jsonResponse = new JSONObject(result);
      JSONObject d = jsonResponse.getJSONObject("data");
      JSONObject user_metadata = d.getJSONObject("user_metadata");
      json.put("metadata", user_metadata);
      callbackContext.success(json);
    } catch (JSONException e) {
      response.error = true;
      callbackContext.error("There was an error fetching the metadata");
    }
    return jsonResponse;
  }

  private void info(CallbackContext callbackContext) {
    JSONObject json = new JSONObject();

    try {
      json.put("deploy_uuid", this.getUUID());
      json.put("binary_version", this.deconstructVersionLabel(this.version_label)[0]);
    } catch (JSONException e) {
      callbackContext.error("Unable to gather deploy info: " + e.toString());
    }

    callbackContext.success(json);
  }

  private void initApp(String app_id) {
    this.app_id = app_id;
    SharedPreferences prefs = this.prefs;

    prefs.edit().putString("app_id", this.app_id).apply();
    // Used for keeping track of the order versions were downloaded
    int version_count = prefs.getInt("version_count", 0);
    prefs.edit().putInt("version_count", version_count).apply();
  }

  private void checkForUpdates(CallbackContext callbackContext, final String channel_tag) {

    String deployed_version = this.prefs.getString("uuid", "");
    JsonHttpResponse response = postDeviceDetails(deployed_version, channel_tag);

    this.parseUpdate(callbackContext, response);
  }

  private void parseUpdate(CallbackContext callbackContext, String response) {
    try {
      JsonHttpResponse jsonResponse = new JsonHttpResponse();
      jsonResponse.json = new JSONObject(response);
      parseUpdate(callbackContext, jsonResponse);
    } catch (JSONException e) {
      logMessage("PARSEUPDATE", e.toString());
      callbackContext.error("Error parsing check response.");
    }
  }

  private void parseUpdate(CallbackContext callbackContext, JsonHttpResponse response) {

    this.last_update = null;
    String ignore_version = this.prefs.getString("ionicdeploy_version_ignore", "");
    String loaded_version = this.prefs.getString("loaded_uuid", "");

    try {
      if (response.json != null) {
        JSONObject update = response.json.getJSONObject("data");
        Boolean compatible = Boolean.valueOf(update.getString("compatible"));
        Boolean updatesAvailable = Boolean.valueOf(update.getString("available"));

        if(!compatible) {
          logMessage("PARSEUPDATE", "Refusing update due to incompatible binary version");
        } else if(updatesAvailable) {
          try {
            String update_uuid = update.getString("snapshot");
            if(!update_uuid.equals(ignore_version) && !update_uuid.equals(loaded_version)) {
              prefs.edit().putString("upstream_uuid", update_uuid).apply();
              this.last_update = update;
            } else {
              updatesAvailable = new Boolean(false);
            }

          } catch (JSONException e) {
            callbackContext.error("Update information is not available");
          }
        }

        if(updatesAvailable && compatible) {
          callbackContext.success("true");
        } else {
          callbackContext.success("false");
        }
      } else {
        logMessage("PARSEUPDATE", "Unable to check for updates.");
        callbackContext.success("false");
      }
    } catch (JSONException e) {
      logMessage("PARSEUPDATE", e.toString());
      callbackContext.error("Error checking for updates.");
    }
  }

  private void downloadUpdate(CallbackContext callbackContext) {
    String upstream_uuid = this.prefs.getString("upstream_uuid", "");

      try {
          String url = this.server;
          final DownloadTask downloadTask = new DownloadTask(this.myContext, callbackContext);
          downloadTask.execute(url);
      } catch (Exception e) {
        logMessage("DOWNLOAD", e.toString());
        callbackContext.error("Error fetching download");
      }
    
  }

  /**
   * Get a list of versions that have been downloaded
   *
   * @return
   */
  private Set<String> getMyVersions() {
    SharedPreferences prefs = this.prefs;
    return prefs.getStringSet("my_versions", new HashSet<String>());
  }


  private JSONArray getDeployVersions() {
    Set<String> versions = this.getMyVersions();
    JSONArray deployVersions = new JSONArray();
    for (String version : versions) {
      String[] version_string = version.split("\\|");
      deployVersions.put(version_string[0]);
    }
    return deployVersions;
  }

  /**
   * Check to see if we already have the version to be downloaded
   *
   * @param uuid
   * @return
   */
  private boolean hasVersion(String uuid) {
    Set<String> versions = this.getMyVersions();

    logMessage("HASVER", "Checking " + uuid + "...");
    for (String version : versions) {
      String[] version_string = version.split("\\|");
      logMessage("HASVER", version_string[0] + " == " + uuid);
      if (version_string[0].equals(uuid)) {
        logMessage("HASVER", "Yes");
        return true;
      }
    }

    logMessage("HASVER", "No");
    return false;
  }

  /**
   * Save a new version string to our list of versions
   *
   * @param uuid
   */
  private void saveVersion(String uuid) {
    SharedPreferences prefs = this.prefs;

    Integer version_count = prefs.getInt("version_count", 0) + 1;
    prefs.edit().putInt("version_count", version_count).apply();

    uuid = uuid + "|" + version_count.toString();

    Set<String> versions = this.getMyVersions();

    versions.add(uuid);

    prefs.edit().putStringSet("my_versions", versions).apply();

    this.cleanupVersions();
  }

  private void cleanupVersions() {
    // Let's keep 5 versions around for now
    SharedPreferences prefs = this.prefs;

    int version_count = prefs.getInt("version_count", 0);
    Set<String> versions = this.getMyVersions();

    if (version_count > 3) {
      int threshold = version_count - 3;

      for (Iterator<String> i = versions.iterator(); i.hasNext();) {
        String version = i.next();
        String[] version_string = version.split("\\|");
        logMessage("VERSION", version);
        int version_number = Integer.parseInt(version_string[1]);
        if (version_number < threshold) {
          logMessage("REMOVING", version);
          i.remove();
          removeVersion(version_string[0]);
        }
      }

      Integer version_c = versions.size();
      logMessage("VERSIONCOUNT", version_c.toString());
      prefs.edit().putStringSet("my_versions", versions).apply();
    }
  }

  private void removeVersionFromPreferences(String uuid) {
    SharedPreferences prefs = this.prefs;
    Set<String> versions = this.getMyVersions();
    Set<String> newVersions = new HashSet<String>();

    for (String version : versions) {
      String[] version_string = version.split("\\|");
      String tempUUID = version_string[0];
      if (!tempUUID.equals(uuid)) {
        newVersions.add(version);
      }
      prefs.edit().putStringSet("my_versions", newVersions).apply();
    }
  }


  /**
   * Remove a deploy version from the device
   *
   * @param uuid
   * @return boolean Success or failure
   */
  private boolean removeVersion(String uuid) {
    if (uuid.equals(this.getUUID())) {
      SharedPreferences prefs = this.prefs;
      prefs.edit().putString("uuid", "").apply();
      prefs.edit().putString("loaded_uuid", "").apply();
    }
    File versionDir = this.myContext.getDir(uuid, Context.MODE_PRIVATE);
    logMessage("REMOVE", "Removing " +  versionDir.getAbsolutePath());
    if (versionDir.exists()) {
      logMessage("REMOVE", "Folder " +  versionDir.getAbsolutePath() + " exists");
      String deleteCmd = "rm -r " + versionDir.getAbsolutePath();
      Runtime runtime = Runtime.getRuntime();
      try {
        runtime.exec(deleteCmd);
        removeVersionFromPreferences(uuid);
        return true;
      } catch (IOException e) {
        logMessage("REMOVE", "Failed to remove " + uuid + ". Error: " + e.getMessage());
      }
    }
    return false;
  }

  private JsonHttpResponse postDeviceDetails(String uuid, final String channel_tag) {
    String endpoint = "/deploy/channels/" + channel_tag + "/check-device";
    JsonHttpResponse response = new JsonHttpResponse();
    JSONObject json = new JSONObject();
    JSONObject device_details = new JSONObject();

    try {
      device_details.put("binary_version", this.deconstructVersionLabel(this.version_label)[0]);
      if(!uuid.equals("")) {
        device_details.put("snapshot", uuid);
      }
      device_details.put("platform", "android");
      json.put("channel_tag", channel_tag);
      json.put("app_id", this.app_id);
      json.put("device", device_details);

      String params = json.toString();
      byte[] postData = params.getBytes("UTF-8");
      int postDataLength = postData.length;

      URL url = new URL(this.server + endpoint);
      HttpURLConnection.setFollowRedirects(true);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Charset", "utf-8");
      conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));

      DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
      wr.write( postData );

      InputStream in = new BufferedInputStream(conn.getInputStream());
      String result = readStream(in);

      JSONObject jsonResponse = new JSONObject(result);

      response.json = jsonResponse;
    } catch (JSONException e) {
      response.error = true;
    } catch (MalformedURLException e) {
      response.error = true;
    } catch (IOException e) {
      response.error = true;
    }

    return response;
  }

  private SharedPreferences getPreferences() {
    // Request shared preferences for this app id
    SharedPreferences prefs = this.myContext.getSharedPreferences("com.ionic.deploy.preferences", Context.MODE_PRIVATE);
    return prefs;
  }

  private String readStream(InputStream is) {
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      int i = is.read();
      while(i != -1) {
        bo.write(i);
        i = is.read();
      }
      return bo.toString();
    } catch (IOException e) {
      return "";
    }
  }

  private void logMessage(String tag, String message) {
    if (this.debug == true) {
      Log.i("IONIC.DEPLOY." + tag, message);
    }
  }

  private void updateVersionLabel(String ignore_version) {
    try {
      String ionicdeploy_version_label = this.constructVersionLabel(this.getAppPackageInfo(), this.getUUID());
      this.prefs.edit().putString("ionicdeploy_version_label", ionicdeploy_version_label).apply();
      this.version_label = prefs.getString("ionicdeploy_version_label", IonicDeploy.NO_DEPLOY_LABEL);
      this.prefs.edit().putString("ionicdeploy_version_ignore", ignore_version).apply();
    } catch (NameNotFoundException e) {
      logMessage("LABEL", "Could not get package info");
    }
  }

  /**
   * Extract the downloaded archive
   *
   * @param zip
   * @param location
   */
  private void unzip(String zip, String location, CallbackContext callbackContext) {
    SharedPreferences prefs = getPreferences();
    String upstream_uuid = prefs.getString("upstream_uuid", "");

    logMessage("UNZIP", upstream_uuid);



    try  {
      FileInputStream inputStream = this.myContext.openFileInput(zip);
      ZipInputStream zipInputStream = new ZipInputStream(inputStream);
      ZipEntry zipEntry = null;

      // Make the version directory in internal storage
      File versionDir = this.myContext.getDir(location, Context.MODE_PRIVATE);

      logMessage("UNZIP_DIR", versionDir.getAbsolutePath().toString());

      // Figure out how many entries are in the zip so we can calculate extraction progress
      ZipFile zipFile = new ZipFile(this.myContext.getFileStreamPath(zip).getAbsolutePath().toString());
      float entries = new Float(zipFile.size());

      logMessage("ENTRIES", "Total: " + (int) entries);

      float extracted = 0.0f;

      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        if (zipEntry.getSize() != 0) {
          File newFile = new File(versionDir + "/" + zipEntry.getName());

          if(newFile.getParentFile().exists() && newFile.getParentFile().isFile()) {
            newFile.getParentFile().delete();
          }        

          newFile.getParentFile().mkdirs();

          byte[] buffer = new byte[2048];

          FileOutputStream fileOutputStream = new FileOutputStream(newFile);
          BufferedOutputStream outputBuffer = new BufferedOutputStream(fileOutputStream, buffer.length);
          int bits;
          while((bits = zipInputStream.read(buffer, 0, buffer.length)) != -1) {
            outputBuffer.write(buffer, 0, bits);
          }

          zipInputStream.closeEntry();
          outputBuffer.flush();
          outputBuffer.close();

          extracted += 1;

          float progress = (extracted / entries) * new Float("100.0f");
          logMessage("EXTRACT", "Progress: " + (int) progress + "%");

          PluginResult progressResult = new PluginResult(PluginResult.Status.OK, (int) progress);
          progressResult.setKeepCallback(true);
          callbackContext.sendPluginResult(progressResult);
        }
      }
      zipInputStream.close();

    } catch(Exception e) {
      //TODO Handle problems..
      logMessage("UNZIP_STEP", "Exception: " + e.getMessage());

      // clean up any zip files dowloaded as they may be corrupted, we can download again if we start over
      String wwwFile = this.myContext.getFileStreamPath(zip).getAbsolutePath().toString();
      if (this.myContext.getFileStreamPath(zip).exists()) {
        String deleteCmd = "rm -r " + wwwFile;
        Runtime runtime = Runtime.getRuntime();
        try {
          runtime.exec(deleteCmd);
          logMessage("REMOVE", "Removed www.zip");
        } catch (IOException ioe) {
          logMessage("REMOVE", "Failed to remove " + wwwFile + ". Error: " + e.getMessage());
        }
      }

      // make sure to send an error
      callbackContext.error(e.getMessage());
      return;
    }



    String wwwFile = this.myContext.getFileStreamPath(zip).getAbsolutePath().toString();
    if (this.myContext.getFileStreamPath(zip).exists()) {
      String deleteCmd = "rm -r " + wwwFile;
      Runtime runtime = Runtime.getRuntime();
      try {
        runtime.exec(deleteCmd);
        logMessage("REMOVE", "Removed www.zip");
      } catch (IOException e) {
        logMessage("REMOVE", "Failed to remove " + wwwFile + ". Error: " + e.getMessage());
      }
    }

    // if we get here we know unzip worked
    this.ignore_deploy = false;


    callbackContext.success("done");
  }

  /**
   * Updates the new index.html, sets the active UUID, and redirects the webview to a given UUID's deploy.
   *
   * @param uuid the UUID of the deploy to redirect to
   **/
  private void redirect(final String uuid) {
    // TODO: get rid of recreatePlugins
    String ignore = this.prefs.getString("ionicdeploy_version_ignore", IonicDeploy.NOTHING_TO_IGNORE);
    if (!uuid.equals("")) {
      prefs.edit().putString("uuid", uuid).apply();
      final File versionDir = this.myContext.getDir(uuid, Context.MODE_PRIVATE);

      try {
        // Parse new index as a string and update the cordova.js reference
        File newIndexFile = new File(versionDir, "index.html");
        final String indexLocation = newIndexFile.toURI().toString();
        String newIndex = this.updateIndexCordovaReference(getStringFromFile(indexLocation));

        // Create the file and directory, if need be 
        versionDir.mkdirs();
        newIndexFile.createNewFile();

        // Save the new index.html
        FileWriter fw = new FileWriter(newIndexFile);
        fw.write(newIndex);
        fw.close();

       
        
        cordova.getActivity().runOnUiThread(new Runnable() {
          @Override
          public void run() {
          /* WebViewClient wvClient = new WebViewClient();
        // Load in the new index.html
         
        WebView webViewObj = new WebView(cordova.getActivity());
        webViewObj.setWebViewClient(wvClient);
        final WebSettings settings = webViewObj.getSettings();
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        */
              logMessage("REDIRECT", "Loading deploy version: " + uuid);
              prefs.edit().putString("loaded_uuid", uuid).apply();
             webView.loadUrlIntoView(indexLocation, false);
              webView.clearHistory();
            
          }
        });
        
      } catch (Exception e) {
        logMessage("REDIRECT", "Pre-redirect cordova injection exception: " + Log.getStackTraceString(e));
      }
    }
  }

  /**
   * Takes an index.html file parsed as a string and updates any extant references to cordova.js contained within to be 
   * valid for deploy.
   *
   * @param indexStr the string contents of index.html
   * @return the updated string index.html
   **/
  private static String updateIndexCordovaReference(String indexStr) {
    // Init the new script
    String newReference = "<script src=\"file:///android_asset/www/cordova.js\"></script>";
    String newConfigReference = "<script src=\"file:///android_asset/www/config.js\"></script>";

    // Define regular expressions
    String commentedRegexString = "<!--.*<script src=(\"|')(.*\\/|)cordova\\.js.*(\"|')>.*<\\/script>.*-->";  // Find commented cordova.js
    String cordovaRegexString = "<script src=(\"|')(.*\\/|)cordova\\.js.*(\"|')>.*<\\/script>";  // Find cordova.js
    String scriptRegexString = "<script.*>.*</script>";  // Find a script tag
    String configRegexString = "<script src=(\"|')(.*\\/|)config\\.js.*(\"|')>.*<\\/script>";  // Find config.js

    // Compile the regexes
    Pattern commentedRegex = Pattern.compile(commentedRegexString);
    Pattern cordovaRegex = Pattern.compile(cordovaRegexString);
    Pattern scriptRegex = Pattern.compile(scriptRegexString);
    Pattern configRegex = Pattern.compile(configRegexString);

    // replace app config path
    indexStr = indexStr.replaceAll(configRegexString, newConfigReference);
    
    // First, make sure cordova.js isn't commented out.
    if (commentedRegex.matcher(indexStr).find()) {
      // It is, let's uncomment it.
      indexStr = indexStr.replaceAll(commentedRegexString, newReference);
    } else {
      // It's either uncommented or missing
      // First let's see if it's uncommented
      if (cordovaRegex.matcher(indexStr).find()) {
        // We found an extant cordova.js, update it
        indexStr = indexStr.replaceAll(cordovaRegexString, newReference);
      } else {
        // No cordova.js, gotta inject it!
        // First, find the first script tag we can
        Matcher scriptMatcher = scriptRegex.matcher(indexStr);
        if (scriptMatcher.find()) {
          // Got the script, add cordova.js below it
          String newScriptTag = String.format("%s\n%s\n", scriptMatcher.group(0), newReference);
        }
      }
    }

    return indexStr;
  }

  private class DownloadTask extends AsyncTask<String, Integer, String> {
    private Context myContext;
    private CallbackContext callbackContext;

    public DownloadTask(Context context, CallbackContext callbackContext) {
      this.myContext = context;
      this.callbackContext = callbackContext;
    }

    @Override
    protected String doInBackground(String... sUrl) {
      InputStream input = null;
      FileOutputStream output = null;
      HttpURLConnection connection = null;
      try {
        URL url = new URL(sUrl[0]);
        HttpURLConnection.setFollowRedirects(true);
        connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        // expect HTTP 200 OK, so we don't mistakenly save error report
        // instead of the file
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
          String msg = "Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage();
          callbackContext.error(msg);
          return msg;
        }

        // this will be useful to display download percentage
        // might be -1: server did not report the length
        float fileLength = new Float(connection.getContentLength());

        logMessage("DOWNLOAD", "File size: " + fileLength);

        // download the file
        input = connection.getInputStream();
        output = this.myContext.openFileOutput("www.zip", Context.MODE_PRIVATE);

        byte data[] = new byte[4096];
        float total = 0;
        int count;
        while ((count = input.read(data)) != -1) {
          total += count;

          output.write(data, 0, count);

          // Send the current download progress to a callback
          if (fileLength > 0) {
            float progress = (total / fileLength) * new Float("100.0f");
            logMessage("DOWNLOAD", "Progress: " + (int) progress + "%");
            PluginResult progressResult = new PluginResult(PluginResult.Status.OK, (int) progress);
            progressResult.setKeepCallback(true);
            callbackContext.sendPluginResult(progressResult);
          }
        }
      } catch (Exception e) {
        callbackContext.error("Something failed with the download...");
        return e.toString();
      } finally {
        try {
          if (output != null)
            output.close();
          if (input != null)
            input.close();
        } catch (IOException ignored) {

        }

        if (connection != null)
          connection.disconnect();
      }

      // Request shared preferences for this app id
      SharedPreferences prefs = getPreferences();

      // Set the saved uuid to the most recently acquired upstream_uuid
      String uuid = prefs.getString("upstream_uuid", "");

      prefs.edit().putString("uuid", uuid).apply();
      callbackContext.success("true");
      return null;
    }
  }
}
