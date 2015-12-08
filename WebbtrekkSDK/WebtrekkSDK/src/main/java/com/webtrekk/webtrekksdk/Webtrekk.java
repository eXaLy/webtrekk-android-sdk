package com.webtrekk.webtrekksdk;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.webtrekk.webbtrekksdk.R;
import com.webtrekk.webtrekksdk.TrackingParameter.Parameter;

/**
 * The WebtrekkSDK main class, the developer/customer interacts with the SDK through this class.
 */
public class Webtrekk {


    //name of the preference strings
    public static final String PREFERENCE_FILE_NAME = "webtrekk-preferences";
    public static final String PREFERENCE_KEY_EVER_ID = "everId";
    public static final String PREFERENCE_APP_VERSIONCODE = "appVersion";
    public static final String PREFERENCE_KEY_OPTED_OUT = "optedOut";
    public static final String PREFERENCE_KEY_IS_SAMPLING = "issampling";
    public static final String PREFERENCE_KEY_SAMPLING = "sampling";
    public static final String PREFERENCE_KEY_INSTALLATION_FLAG = "InstallationFlag";
    public static final String PREFERENCE_KEY_CONFIGURATION = "webtrekkTrackingConfiguration";
    public static final String TRACKING_LIBRARY_VERSION = "400";
    public static final String TRACKING_LIBRARY_VERSION_UA = "4.0";


    private RequestUrlStore requestUrlStore;
    private TrackingConfiguration trackingConfiguration;

    private boolean isSampling;
    private boolean isOptout;

    // the available plugins
    private ArrayList<Plugin> plugins;

    // this always contains the name of the current activity as string, important for auto naming button clicks or other inner class events
    private String currentActivityName;

    //determins the number of currently running activitys
    private int activityCount;

    private ScheduledExecutorService timerService;
    private ExecutorService executorService;
    private Future<?> requestProcessorFuture;

    private Context context;
    // this tracking params allows to add parameters globally to all tracking requests in the configured app
    // this values can also be configured in the xml file and will be overriten by the values configured there
    private TrackingParameter globalTrackingParameter;

    //additional customer params, this is a global avaiable hashmap with key, values,
    //before the requests are send the keys here are matched with the keys in the xml configurion or the global/
    //local tracking params, and override them, so for example key = orientation, value = horizontal
    //in the xml configuraton then is the trackingparameter requests defined with ecomerce_parameter "1" and the key orientation
    // before the request url is generated this keys will be replaced with values from this map
    // the customParameter are set by the user and are only valid for the current activity
    private Map<String, String> customParameter;
    private Map<String, String> autoCustomParameter;

    // this hashmap contains all the default parameter which are defined by webtrekk and have an url mapping
    private HashMap<Parameter, String> webtrekkParameter;

    // this TrackingParameter object contains keys or parameter which needs to be send to manage the internal state
    // this are only 1 once, for one request, the customer must not have access to this ones,
    // after the where appended to the next request they will be removed here from this object again
    // this will be used for example for force new session, one, or app first installed
    private TrackingParameter internalParameter;

    private TrackedActivityLifecycleCallbacks callbacks;



    /**
     * non public constructor to create a Webtrekk Instance as
     * makes use of the Singleton Pattern here.
     */

    Webtrekk() {
    }

    // https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom
    private static class SingletonHolder {
        static final Webtrekk webtrekk = new Webtrekk();
    }

    /**
     * public method to get the singleton instance of the webtrekk object,
     * @return webtrekk instance
     */
    public static Webtrekk getInstance() {
        return SingletonHolder.webtrekk;
    }

    final public void initWebtrekk(final Application app) {
        if (app == null) {
            throw new IllegalArgumentException("no valid app");
        }
        initAutoTracking(app);
        initWebtrekk(app.getApplicationContext());
    }


    /**
     * this initializes the webtrekk tracking configuration, it has to be called only once when the
     * application starts, for example in the Application Class or the Main Activitys onCreate
     * @param c the application context / context of the main activity
     *
     */
    public void initWebtrekk(final Context c) {
        if (c == null) {
            throw new IllegalArgumentException("no valid context");
        }
        if (this.context != null) {
            //this can also occur on screen orientation changes
            //TODO: recheck desired behaviour
            return;
            //throw new IllegalStateException("The initWebtrekk method must be called only once");
        }
        this.context = c;

        if(customParameter == null) {
            customParameter = new HashMap<>();
        }

        initTrackingConfiguration();
        initOptedOut();
        initSampling();
        initInternalParameter();
        initWebtrekkParameter();
        initAutoCustomParameter();
        initPlugins();
        initTimerService();
        initAdvertiserId();
        //TODO: make sure this can not break
        //Application act = (Application) context.getApplicationContext();


        this.requestUrlStore = new RequestUrlStore(context, trackingConfiguration.getMaxRequests());
        globalTrackingParameter = new TrackingParameter();


        WebtrekkLogging.log("requestUrlStore created: max requests - " + trackingConfiguration.getMaxRequests());

        WebtrekkLogging.log("tracking initialized");

    }

    final void initTrackingConfiguration() {
        initTrackingConfiguration(null);
    }

    void initInternalParameter() {
        if(internalParameter == null) {
            internalParameter = new TrackingParameter();
        }
        // first initalization of the webtrekk instance, so set fns to 1
        internalParameter.add(Parameter.FORCE_NEW_SESSION, "1");
        // if the app is started for the first time, the param "one" is 1 otherwise its always 0
        if(HelperFunctions.firstStart(context)) {
            internalParameter.add(Parameter.APP_FIRST_START, "1");
        } else {
            internalParameter.add(Parameter.APP_FIRST_START, "0");
        }
    }

    /**
     * initializes the tracking configuration, either from xml, or shared prefs, or remote
     * always takes the newest version, and stores it in the shared preferences for future reference
     * in case it can not get a new remote config and remote config is enabled, the old config is used
     *
     * @param configurationString for unit testing only
     */
    final void initTrackingConfiguration(final String configurationString) {

            // always parse the local raw config version first, this is fallback, default and also the way to fix broken online configs
            //TODO: this could me more elegant by only parsing it when its a new app version which needs to be set anyway
            String trackingConfigurationString;
            String defaultConfigurationString = null;
            if (configurationString == null) {
                try {
                    trackingConfigurationString = HelperFunctions.stringFromStream(context.getResources().openRawResource(R.raw.webtrekk_config));
                } catch (IOException e) {
                    WebtrekkLogging.log("no custom config was found, illegal state, provide a valid config in res/raw/webtrekk_config.xml");
                    throw new IllegalStateException("can not load xml configuration file, invalid state");
                }
                if(trackingConfigurationString.length() < 80) {
                    // neccesary to make sure it uses the placeholder which has 66 chars length
                    WebtrekkLogging.log("no custom config was found, illegal state, provide a valid config in res/raw/webtrekk_config.xml");
                    throw new IllegalStateException("can not load xml configuration file, invalid state");
                }
            } else {
                trackingConfigurationString = configurationString;
            }

        try {
            // parse default configuration without default, willl throw exceptions when its not valid
            trackingConfiguration = new TrackingConfigurationXmlParser().parse(trackingConfigurationString);
        } catch (Exception e) {
            throw new IllegalStateException("invalid xml configuration file, invalid state");
        }

        if(trackingConfiguration != null && trackingConfiguration.isEnableRemoteConfiguration()) {
            SharedPreferences sharedPrefs = context.getSharedPreferences(Webtrekk.PREFERENCE_FILE_NAME, Context.MODE_PRIVATE);
            // second check if a newer remote config version is stored locally
            if(sharedPrefs.contains(Webtrekk.PREFERENCE_KEY_CONFIGURATION)) {
                WebtrekkLogging.log("found trackingConfiguration in preferences");
                // in this case we already have a configuration xml stored
                // parse the existing one and check if an update is online available
                trackingConfigurationString = sharedPrefs.getString(Webtrekk.PREFERENCE_KEY_CONFIGURATION, null);
                TrackingConfiguration sharedPreferencetrackingConfiguration = null;
                try {
                    sharedPreferencetrackingConfiguration = new TrackingConfigurationXmlParser().parse(trackingConfigurationString);
                    if(sharedPreferencetrackingConfiguration.getVersion() > trackingConfiguration.getVersion()) {
                        // in this case there is a newer, so replace th old one
                        trackingConfiguration = sharedPreferencetrackingConfiguration;
                    }
                } catch (IOException e) {
                    WebtrekkLogging.log("ioexception parsing the configuration string", e);
                } catch(XmlPullParserException e) {
                    WebtrekkLogging.log("exception parsing the configuration string", e);
                }


            }
            // third check online for newer versions
            //TODO: maybe store just the version number locally in preferences might reduce some parsing
            new TrackingConfigurationDownloadTask(this, null).execute(trackingConfiguration.getTrackingConfigurationUrl());
        }

        // check if we have a valid configuration
        if(trackingConfiguration != null && trackingConfiguration.validateConfiguration()) {
            WebtrekkLogging.log("xml trackingConfiguration value: trackid - " + trackingConfiguration.getTrackId());
            WebtrekkLogging.log("xml trackingConfiguration value: trackdomain - " + trackingConfiguration.getTrackDomain());
            WebtrekkLogging.log("xml trackingConfiguration value: send_delay - " + trackingConfiguration.getSendDelay());

            for(ActivityConfiguration cfg : trackingConfiguration.getActivityConfigurations().values()) {
                WebtrekkLogging.log("xml trackingConfiguration activity for: " + cfg.getClassName() + " mapped to: " + cfg.getMappingName() + " autotracked: " + cfg.isAutoTrack());
            }
        } else {
            WebtrekkLogging.log("error loading the configuration - can not initialize tracking");
            throw new IllegalStateException("could not get valid configuration, invalid state");
        }

        WebtrekkLogging.log("tracking configuration initialized");

    }

    /**
     * load the enabled plugins, this will decided by hand during compile time of the lib to improve performance
     * each customer can that way have a lib with all the features/plugins he needs
     * he has to enable the plugin in the xml to load it here, therefore each plugin needs a unique name
     */
    void initPlugins() {
        plugins = new ArrayList<>();
        if(trackingConfiguration.isEnablePluginHelloWorld()){
            plugins.add(new HelloWorldPlugin(this));
            WebtrekkLogging.log("loaded plugin: HelloWorldPlugin");
        }
        WebtrekkLogging.log("all plugins loaded");
    }

    /**
     * starts the timer service, it executes after initial send delay for the first time, and then
     * every sendDelay seconds, it processes the stored requests in a separate thread
     */
    void initTimerService() {
        // start the timer service
        timerService = Executors.newSingleThreadScheduledExecutor();
        timerService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                onSendIntervalOver();
            }
        }, trackingConfiguration.getSendDelay(), trackingConfiguration.getSendDelay(), TimeUnit.SECONDS);
        WebtrekkLogging.log("timer service started");
    }

    /**
     * initializes the opt out based on the shared preference settings
     */
    void initOptedOut() {
        SharedPreferences preferences = this.context.getSharedPreferences(PREFERENCE_FILE_NAME, Context.MODE_PRIVATE);
        this.isOptout = preferences.getBoolean(PREFERENCE_KEY_OPTED_OUT, false);
        WebtrekkLogging.log("optedOut = " + this.isOptout);
    }

    /**
     * initializes the sampling, which means that if a sampling value X is configured, only
     * every X user data will be tracked, sampling will be stores in the shared prefs once initialized
     * it can be reset with changing the xml config
     */
    void initSampling() {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCE_FILE_NAME, Context.MODE_PRIVATE);

        if(preferences.contains(PREFERENCE_KEY_IS_SAMPLING)) {
            // key exists so set sampling value and return
            isSampling = preferences.getBoolean(PREFERENCE_KEY_IS_SAMPLING, false);
            // check if the sampling value is unchanged, if so return
            if(preferences.getInt(PREFERENCE_KEY_SAMPLING, -1) == trackingConfiguration.getSampling()) {
                return;
            }
        }
        // from here on they sampling either changed or is missing, so reinitialize it
        SharedPreferences.Editor editor = preferences.edit();
        // calculate if the device is sampling
        if(trackingConfiguration.getSampling()>1) {
            isSampling = (Long.valueOf(getEverId()) % trackingConfiguration.getSampling()) != 0;
        } else {
            isSampling = false;
        }
        // store the preference keys if the device is sampling and the sampling value
        editor.putBoolean(PREFERENCE_KEY_IS_SAMPLING, isSampling);
        editor.putInt(PREFERENCE_KEY_SAMPLING, trackingConfiguration.getSampling());
        editor.commit();
        WebtrekkLogging.log("isSampling = " + this.isSampling + ", samplingRate = " + trackingConfiguration.getSampling());
    }

    /**
     * collecting all data and set the appropiate parameter
     * this functions inserts all data into the webtrekkParameter HashMap for which tracking is enabled in the xml
     * customers can decide on their own based on their xml tracking config, which values they are interested in
     * this method initializes the webtrekkparameter hashmap, so all parameters for which an url is defined
     */
    void initWebtrekkParameter() {
        // collect all static device information which remain the same for all requests
        if(webtrekkParameter == null) {
            webtrekkParameter = new HashMap<>();
        }

        webtrekkParameter.put(Parameter.SCREEN_DEPTH, HelperFunctions.getDepth(context));
        webtrekkParameter.put(Parameter.TIMEZONE, HelperFunctions.getTimezone());
        webtrekkParameter.put(Parameter.USERAGENT, HelperFunctions.getUserAgent());
        webtrekkParameter.put(Parameter.DEV_LANG, HelperFunctions.getCountry());



        // for comatilility reasons always add the sampling rate param to the url
        webtrekkParameter.put(Parameter.SAMPLING, "" + trackingConfiguration.getSampling());

        // always track the wt everid
        webtrekkParameter.put(Parameter.EVERID, HelperFunctions.getEverId(context));


        WebtrekkLogging.log("collected static automatic data");
    }

    /**
     * this method initializes the custom parameter values which are predefined by webtrekk
     * the customer can also add new ones as he likes, unknown entries will be ingored by the server
     */
    public void initAutoCustomParameter() {
        if(autoCustomParameter == null) {
            autoCustomParameter = new HashMap<>();
        }
        if(customParameter == null) {
            customParameter = new HashMap<>();
        }

        if(trackingConfiguration.isAutoTrackAppVersionName()) {
            autoCustomParameter.put("appVersion", HelperFunctions.getAppVersionName(context));

        }
        if(trackingConfiguration.isAutoTrackAppVersionCode()) {
            autoCustomParameter.put("appVersionCode", String.valueOf(HelperFunctions.getAppVersionCode(context)));

        }
        if(trackingConfiguration.isAutoTrackPlaystoreUsername()) {
            Map<String, String> playstoreprofile = HelperFunctions.getUserProfile(context);
            autoCustomParameter.put("playstoreFamilyname", playstoreprofile.get("sname"));
            autoCustomParameter.put("playstoreGivenname", playstoreprofile.get("gname"));

        }
        if(trackingConfiguration.isAutoTrackPlaystoreMail()) {
            //Map<String, String> playstoreprofile = HelperFunctions.getUserProfile(context);
            //customParameter.put("playstoreMail", playstoreprofile.get("email"));
            autoCustomParameter.put("playstoreMail", HelperFunctions.getMailByAccountManager(context));


        }
        if (trackingConfiguration.isAutoTrackAppPreInstalled()) {
            autoCustomParameter.put("appPreinstalled", String.valueOf(HelperFunctions.isAppPreinstalled(context)));

        }
        // if the app was updated, send out the update request once

        if(trackingConfiguration.isAutoTrackAppUpdate()) {
            int currentVersion = HelperFunctions.getAppVersionCode(context);
            // store the app version code to check for updates
            if(HelperFunctions.firstStart(context)) {
                HelperFunctions.setAppVersionCode(currentVersion, context);
            }

            if(HelperFunctions.updated(context, currentVersion)) {
                autoCustomParameter.put("appUpdated", "1");
            } else  {
                autoCustomParameter.put("appUpdated", "0");
            }

        }
        if(trackingConfiguration.isAutoTrackApiLevel()) {
            autoCustomParameter.put("apiLevel", HelperFunctions.getAPILevel());

        }

    }

    /**
     * reads the advertiser id and advertiser opt out values for the user,
     * needs the appropiate permissions and playstore lib linked, at least the ad parts
     * works by passing in a reference as it runs in a seperate thread to avoid lags in the main thread,
     */
    synchronized void initAdvertiserId() {
        if(!trackingConfiguration.isAutoTrackAdvertiserId()) {
            return;
        }
        // check if playservice sdk is available on that device, TODO: define alternative handling here
        // TODO: define default handling when this values can not be read, maybe cache them in Shared preferences if that is allowed due to opt out
        if( GooglePlayServicesUtil.isGooglePlayServicesAvailable(context) == 0) {
            Thread advertiserIdThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    WebtrekkLogging.log("starting advertiser id thread");
                    AdvertisingIdClient.Info adInfo = null;
                    try {
                        adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context);

                        WebtrekkLogging.log("advertiserId: " + adInfo.getId());
                        Webtrekk.this.autoCustomParameter.put("advertiserId", adInfo.getId());
                        Webtrekk.this.autoCustomParameter.put("advertisingOptOut", String.valueOf(adInfo.isLimitAdTrackingEnabled()));
                        //app.getTracker()..getAutoTrackedValues().put(TrackingParams.Params.ADVERTISER_ID, adInfo.getId());
                        //app.getWTRack().getAutoTrackedValues().put(TrackingParams.Params.ADVERTISER_OPTOUT, String.valueOf(adInfo.isLimitAdTrackingEnabled()));

                    } catch (IOException e) {
                        // Unrecoverable error connecting to Google Play services (e.g.,
                        // the old version of the service doesn't support getting AdvertisingId).
                        WebtrekkLogging.log("Unrecoverable error connecting to Google Play services", e);

                    } catch (GooglePlayServicesNotAvailableException e) {
                        // Google Play services is not available entirely.
                        WebtrekkLogging.log("GooglePlayServicesNotAvailableException", e);
                    } catch (GooglePlayServicesRepairableException e) {
                        // maybe will work with another try, recheck
                    } catch (NullPointerException e) {
                        // adinfo was null or could not get the id/optout setting
                        WebtrekkLogging.log("Unrecoverable error connecting to Google Play services", e);
                    }
                }
            });
            try {
                advertiserIdThread.start();
            } catch (Exception e){
                WebtrekkLogging.log("error getting the advertiser id");
            }
        } else {
            WebtrekkLogging.log("google play services not available on device");
        }
    }


    /**
     * this method updates the webtrekk and customer parameter which change with every request
     * @return
     */
    void updateDynamicParameter() {
        // put the screen orientation to into the custom parameter, will change with every request
        if(autoCustomParameter != null) {
            autoCustomParameter.put("screenOrientation", HelperFunctions.getOrientation(context));
            autoCustomParameter.put("connectionType", HelperFunctions.getConnectionString(context));
        }

        if(requestUrlStore != null) {
            autoCustomParameter.put("requestUrlStoreSize", String.valueOf(requestUrlStore.size()));
        }
        if(webtrekkParameter != null) {
            // also update the webtrekk parameter
            webtrekkParameter.put(Parameter.SCREEN_RESOLUTION, HelperFunctions.getResolution(context));
        }
    }



    /**
     * this functions enables the automatic activity tracking in case its enabled in the configuration
     * @param app application object of the tracked app, can either be a custom one or required by getApplication()
     */
    public void initAutoTracking(Application app){
//        boolean autoTrack = false;
//        // when global autotracking is enabled, autoTrack is true
//        if(trackingConfiguration.isAutoTracked()) {
//            autoTrack = true;
//        }
//        // enable autotracking when one of the activities has autoTracking enabled
//        for(ActivityConfiguration activityConfiguration : trackingConfiguration.getActivityConfigurations().values()) {
//            if(activityConfiguration.isAutoTrack()) {
//                autoTrack = true;
//            }
//        }
        if(callbacks == null) {
            WebtrekkLogging.log("enabling callbacks");
            callbacks = new TrackedActivityLifecycleCallbacks(this);
            app.registerActivityLifecycleCallbacks(callbacks);
        }
    }


    /**
     * this method immediately stops tracking, for example when a user opted out
     * tracking will be in invalid state, until init is called again
     */
    public synchronized  void stopTracking() {
        if(requestUrlStore != null) {
            requestUrlStore.clear();
        }
        activityCount = 0;
    }

    /**
     * checks if logging is enabled
     * @return boolean if logging is enabled
     */
    public static boolean isLoggingEnabled() {
        return WebtrekkLogging.isLogging();
    }

    /**
     * enables the logging for all SDK log outputs
     * @param logging enables/disables the webtrekk logging
     */
    public static void setLoggingEnabled(boolean logging) {
        WebtrekkLogging.setIsLogging(logging);
    }

    public boolean isSampling() {
        return isSampling;
    }

    /**
     * returns the context with which the webtrekk instance was initialized, this can not be changed
     *
     * @return context
     */
    public Context getContext() {
        return context;
    }

    public String getCurrentActivityName() {
        return this.currentActivityName;
    }

    public void setCurrentActivityName(String currentActivityName) {
        this.currentActivityName = currentActivityName;
    }


    /**
     * this is the default tracking method which creates an empty tracking trackingParameter object
     * it only tracks the auto tracked values like lib version, resolution, page name
     */
    public void track() {
        track(new TrackingParameter());
    }

    /**
     * this method gets called when auto tracking is enabled and one of the lifycycle methods is called
     */
    public void autoTrackActivity() {
        // only track if auto tracking is enabled for that activity
        // the default value and the activities autoTracked value is based on the global xml settings
        boolean autoTrack = trackingConfiguration.isAutoTracked();
        if(trackingConfiguration.getActivityConfigurations()!= null && trackingConfiguration.getActivityConfigurations().containsKey(currentActivityName)) {
            autoTrack = trackingConfiguration.getActivityConfigurations().get(currentActivityName).isAutoTrack();
        }
        if(autoTrack) {
            track();
        }
    }

    /**
     * allows tracking of a requests with a custom set of trackingparams
     *
     * @param tp the TrackingParams for the request
     * @throws IllegalStateException when the SDK has not benn initialized, activity was not started or the trackingParameter are invalid
     */
    public void track(final TrackingParameter tp) {
        if (requestUrlStore == null || trackingConfiguration == null) {
            WebtrekkLogging.log("webtrekk has not been initialized");
            return;
        }
        if (activityCount == 0) {
            WebtrekkLogging.log("no running activity, call startActivity first");
            return;
        }
        if(tp == null) {
            WebtrekkLogging.log("TrackingParams is null");
            return;
        }

        // use the automatic name in case no activity name is given
        // for calls from class methods this must be overwritten
        //if(!tp.getDefaultParameter().containsKey(TrackingParams.Params.ACTIVITY_NAME)) {
        // hack for now, reflection not possible, Thread.currentThread().getStackTrace()[2].getClassName() is slower, custom security maanger to much
        // automatically adds the name of the calling activity or class to the trackingparams
        //String activity_name = new Throwable().getStackTrace()[2].getClassName();
        //tp.add(TrackingParams.Params.ACTIVITY_NAME, activity_name);
        //}
        // other way was cooler, but requirements where to allow setting it always manually


        TrackingRequest request = createTrackingRequest(tp);
        addRequest(request);
    }

    /**
     * this method creates a trackingrequest object and applys the various overrides from the xml configuration
     * this honours the hirarchy of overriding the values
     *
     * @param tp
     * @return
     */
    TrackingRequest createTrackingRequest(TrackingParameter tp) {
        // create a new trackingParameter object
        TrackingParameter trackingParameter = new TrackingParameter();
        // add the name of the current activity
        trackingParameter.add(Parameter.ACTIVITY_NAME, currentActivityName);

        trackingParameter.add(Parameter.TIMESTAMP, String.valueOf(System.currentTimeMillis()));

        // action params are a special case, no other params but the ones given as parameter in the code
        if(tp.containsKey(Parameter.ACTION_NAME)) {
            //when its an action only resolution and depth are neccesary
            trackingParameter.add(Parameter.SCREEN_RESOLUTION, webtrekkParameter.get(Parameter.SCREEN_RESOLUTION));
            trackingParameter.add(Parameter.SCREEN_DEPTH, webtrekkParameter.get(Parameter.SCREEN_DEPTH));
            trackingParameter.add(Parameter.USERAGENT, webtrekkParameter.get(Parameter.USERAGENT));
            trackingParameter.add(tp);
            return new TrackingRequest(trackingParameter, trackingConfiguration);
        }

        // update the dynamic parameter which change with every request
        updateDynamicParameter();
        // add the default parameter
        trackingParameter.add(webtrekkParameter);


        // first add the globally configured trackingparams which are defined in the webtrekk.globalTrackingParameter, if there are any
        if(globalTrackingParameter != null) {
            trackingParameter.add(globalTrackingParameter);
        }
        // second add the globally configured trackingparams from the xml which may override the ones above
        if(trackingConfiguration.getGlobalTrackingParameter() != null) {
            trackingParameter.add(trackingConfiguration.getGlobalTrackingParameter());
        }

        // third add the local ones from the activity which may override all of the above params, this are passed from the track call
        trackingParameter.add(tp);
        //forth add the local ones which each activity has defined in its xml configuration, they will override the ones above
        //TODO: make this better code, basicly check that the activity has params configured
        if(trackingConfiguration.getActivityConfigurations()!= null && trackingConfiguration.getActivityConfigurations().containsKey(currentActivityName)){
            ActivityConfiguration activityConfiguration = trackingConfiguration.getActivityConfigurations().get(currentActivityName);
            if(activityConfiguration != null) {
                if(activityConfiguration.getActivityTrackingParameter() != null) {
                    trackingParameter.add(activityConfiguration.getActivityTrackingParameter());
                }
                // override the activityname if a mapping name is given
                if(activityConfiguration.getMappingName() != null) {
                    trackingParameter.add(Parameter.ACTIVITY_NAME, activityConfiguration.getMappingName());
                }
            }
        }

        //last step add the internal parameter
        trackingParameter.add(internalParameter);
        //now map the string values from the xml/code tracking parameters to the custom values defined by webtrekk or the customer
        if(customParameter!= null) {
            customParameter.putAll(autoCustomParameter);
            trackingParameter.applyMapping(customParameter);
        }


        return new TrackingRequest(trackingParameter, trackingConfiguration);

    }

    /**
     * Stores the generated URLS of the requests in the local RequestUrlStore until they are send
     * by the timer function. It is also responsible for executing the plugins before and after the request
     * the plugins will be always executed no matter if the user opted out or not
     *
     * @param request the Tracking Request
     */
    private void addRequest(TrackingRequest request)  {

        // execute the before plugin functions
        for(Plugin p: plugins){
            p.before_request(request);
        }
        // only track when not opted out, but always execute the plugins
        if(!isOptout && !isSampling) {
            String urlString = request.getUrlString();
            WebtrekkLogging.log("adding url: " + urlString);
            requestUrlStore.add(request.getUrlString());
        }

        // execute the after_request plugin functions
        for(Plugin p: plugins){
            p.after_request(request);
        }

        // after the url is created reset the internal parameters to zero
        //TODO: special case where they could not be send and the url got removed?
        internalParameter.add(Parameter.FORCE_NEW_SESSION, "0");
        internalParameter.add(Parameter.APP_FIRST_START, "0");
        autoCustomParameter.put("appUpdated", "0");
    }


    /**
     * this developer has to call this function each time a new activity starts, except when he uses auto tracking
     * best place to call this is during the activitys onStart method, it also allows overriding the
     * current activity name, which gets tracked
     *
     * @param activityName a string containing the name of the activity
     */
    public void startActivity(String activityName) {
        if (requestUrlStore == null || trackingConfiguration == null) {
            throw new IllegalStateException("webtrekk has not been initialized");
        }
        activityCount++;
        this.currentActivityName = activityName;
        if(activityCount == 1) {
            onFirstActivityStart();
        }
    }

    /**
     * this method gets called when the first activity of the application has started
     * it loads the old requests from the backupfile and trys to send them
     *
     */
    private void onFirstActivityStart() {
        requestUrlStore.loadRequestsFromFile();
        // remove the old backupfile after the requests are loaded into memory/requestUrlStore
        requestUrlStore.deleteRequestsFile();
        onSendIntervalOver();
    }

    /**
     * this has to be called in every Activitys onStop method, that way the SDk can track the current
     * open activities and knows when to exit
     */
    public void stopActivity() {
        if (requestUrlStore == null || trackingConfiguration == null) {
            throw new IllegalStateException("webtrekk has not been initialized");
        }
        if (activityCount == 0) {
            throw new IllegalStateException("activity has not been started yet, call startActivity");
        }
        activityCount--;

        //always clear the current activities custom parameter
        customParameter.clear();
        if(activityCount == 0) {
            onLastActivityStop();
        }
    }

    /**
     * this method gets called when the last activtiy closes, it trys so send the remaining requests
     * and store the rest if sending fails
     */
    private void onLastActivityStop() {
        onSendIntervalOver();
        requestUrlStore.saveRequestsToFile();
    }

    /**
     * this method gets called whenever the send delay is over, it executes the requesthandler in a
     * new thread
     */
    void onSendIntervalOver() {
        WebtrekkLogging.log("onSendIntervalOver: activity count: " + activityCount + " request urls: " + requestUrlStore.size());
        if(requestUrlStore.size() > 0  && (requestProcessorFuture == null || requestProcessorFuture.isDone())) {
            if (executorService == null) {
                executorService = Executors.newSingleThreadExecutor();
            }
            requestProcessorFuture = executorService.submit(new RequestProcessor(requestUrlStore));
        }
    }

    void setContext(Context context) {
        this.context = context;
    }

    Map<TrackingParameter.Parameter, String> getWebtrekkParameter() {
        return webtrekkParameter;
    }


    public boolean isOptout() {
        return isOptout;
    }


    /**
     * this method is for the opt out switcht, when called it will set the shared preferences of opt out
     * and also stops tracking in case the user opts out
     * @param oo boolean value indicating if the user opted out or not
     */
    public void setOptout(boolean oo) {
        if (this.isOptout == oo) {
            return;
        }
        this.isOptout = oo;

        SharedPreferences preferences = this.context.getSharedPreferences(PREFERENCE_FILE_NAME, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(PREFERENCE_KEY_OPTED_OUT, isOptout).commit();
    }

    /**
     * allows to set global tracking parameter which will be added to all requests
     * @return
     */
    public TrackingParameter getGlobalTrackingParameter() {
        return globalTrackingParameter;
    }

    public void setGlobalTrackingParameter(TrackingParameter globalTrackingParameter) {
        this.globalTrackingParameter = globalTrackingParameter;
    }

    /**
     * this method allows the customer to access the custom parameters which will be replaced by the mapping lter
     *
     * @return
     */
    public Map<String, String> getCustomParameter() {
        return customParameter;
    }

    /**
     * this method alles the customer to set the custom parameters map
     *
     */
    public void setCustomParameter(Map<String, String> customParameter) {
        this.customParameter = customParameter;
    }

    public String getEverId() {
        return HelperFunctions.getEverId(context);
    }
    /**
     * for unit testing only
     * @param requestUrlStore
     */
    void setRequestUrlStore(RequestUrlStore requestUrlStore) {
        this.requestUrlStore = requestUrlStore;
    }

    /**
     * for unit testing only
     * @return
     */
    RequestUrlStore getRequestUrlStore() {
        return requestUrlStore;
    }
    /**
     * for unit testing only
     * @return
     */
    int getActivityCount() {
        return activityCount;
    }
    /**
     * for unit testing only
     * @return
     */
    ScheduledExecutorService getTimerService() {
        return timerService;
    }
    /**
     * for unit testing only
     * @return
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }
    /**
     * for unit testing only
     * @return
     */
    public Future<?> getRequestProcessorFuture() {
        return requestProcessorFuture;
    }
    /**
     * for unit testing only
     * @return
     */
    List<Plugin> getPlugins() {
        return plugins;
    }

    /**
     * for unittesting only
     * @return
     */
    TrackingConfiguration getTrackingConfiguration() {
        return trackingConfiguration;
    }

    /**
     * for unit testing only
     * @param trackingConfiguration
     */
    void setTrackingConfiguration(TrackingConfiguration trackingConfiguration) {
        this.trackingConfiguration = trackingConfiguration;
    }

    /**
     * for unittesting only
     * @return
     */
    public TrackingParameter getInternalParameter() {
        return internalParameter;
    }

    /**
     * for unit testing in the application and debugging
     */
    public int getVersion() { return trackingConfiguration.getVersion(); }
    public String getTrackDomain() { return trackingConfiguration.getTrackDomain(); }
    public String getTrackId() { return trackingConfiguration.getTrackId(); }
    public int getSampling() { return trackingConfiguration.getSampling(); }
    public void setIsSampling(boolean isSampling ) { this.isSampling = isSampling; }
    public int getSendDelay() { return trackingConfiguration.getSendDelay(); }
    public int getResendOnStartEventTime() { return trackingConfiguration.getResendOnStartEventTime(); }
    public int getMaxRequests() { return trackingConfiguration.getMaxRequests(); }
    public String getTrackingConfigurationUrl() { return trackingConfiguration.getTrackingConfigurationUrl(); }
    public boolean isAutoTracked() { return trackingConfiguration.isAutoTracked(); }
    public boolean isAutoTrackApiLevel() { return trackingConfiguration.isAutoTrackApiLevel(); }
    public boolean isEnableRemoteConfiguration() { return trackingConfiguration.isEnableRemoteConfiguration(); }

    Map<String, String> getAutoCustomParameter() {
        return autoCustomParameter;
    }

    void setAutoCustomParameter(Map<String, String> autoCustomParameter) {
        this.autoCustomParameter = autoCustomParameter;
    }
}