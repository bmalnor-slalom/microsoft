

package nl.microsoft.bizmilesapp.azuredemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.location.Geocoder;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import nl.microsoft.bizmilesapp.azuredemo.nl.microsoft.bizmilesapp.azuredemo.services.StartFetchAddressIntentService;
import nl.microsoft.bizmilesapp.azuredemo.nl.microsoft.bizmilesapp.azuredemo.services.StopFetchAddressIntentService;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.microsoft.windowsazure.mobileservices.MobileServiceClient;
import com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider;
import com.microsoft.windowsazure.mobileservices.authentication.MobileServiceUser;
import com.microsoft.windowsazure.mobileservices.http.OkHttpClientFactory;
import com.microsoft.windowsazure.mobileservices.table.DateTimeOffset;
import com.microsoft.windowsazure.mobileservices.table.MobileServiceTable;
import com.microsoft.windowsazure.mobileservices.table.query.QueryOrder;
import com.squareup.okhttp.OkHttpClient;


import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Time;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import javax.mail.internet.AddressException;
//import javax.mail.internet.InternetAddress;

import static com.microsoft.windowsazure.mobileservices.table.query.QueryOperations.val;

public class MainActivity extends AppCompatActivity implements ConnectionCallbacks, OnConnectionFailedListener,LocationListener  {

    protected static final String TAG = "main-activity";

    protected static final String ADDRESS_REQUESTED_KEY = "address-request-pending";
    protected static final String LOCATION_ADDRESS_KEY = "location-address";

    //  Azure Stuff
    private MobileServiceClient amsDBClient;
    private MobileServiceTable<Ride> mRidesTable;


    //  Screen elements
    protected Location mLastLocation;
    protected boolean mStartAddressRequested;
    protected boolean mStopAddressRequested;
    protected String mAddressOutput;
    protected TextView mLocationAddressTextView;
    protected ListView mRidelist;
    protected ProgressBar mProgressBar;
    protected Button mStartButton;
    protected Button mStopButton;
    protected Button mSendMailButton;
    protected EditText mMailadres;

    private ArrayAdapter<String> adapter;
    private ArrayList<String> arrayList;

    //  Google API stuff
    protected GoogleApiClient mGoogleApiClient;
    protected GoogleApiClient client;
    protected LocationRequest mLocationRequest;

    //  DroidServices
    Intent stopFetchIntent = null;
    Intent startFetchintent = null;

    private BizMilesStartReceiver mStartResultReceiver = new BizMilesStartReceiver(new Handler());
    private BizMilesStopReceiver mStopResultReceiver = new BizMilesStopReceiver(new Handler());


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        mLocationAddressTextView = (TextView) findViewById(R.id.location_address_view);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
        mStartButton = (Button) findViewById(R.id.start_button);
        mStopButton = (Button) findViewById(R.id.stop_button);
        mSendMailButton = (Button) findViewById(R.id.sendmail_button);
        mRidelist = (ListView) findViewById(R.id.ridelist);
        mMailadres = (EditText) findViewById(R.id.mailadres);

        arrayList = new ArrayList<String>();
        adapter = new ArrayAdapter<String>(getApplicationContext(), android.R.layout.simple_spinner_item, arrayList);
        mRidelist.setAdapter(adapter);

        // Set defaults, then update using values stored in the Bundle.
        mStartAddressRequested = false;
        mStopAddressRequested = false;
        mAddressOutput = "";

        updateValuesFromBundle(savedInstanceState);

        //  Google API init
        buildGoogleApiClient();
        //  This was genereated by the ide for API indexing
        if(client==null)
            client = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();
        createLocationRequest();

        //  Azure API init
        initAzureMSConnection();

        if(isMyServiceRunning(StartFetchAddressIntentService.class)){
            Toast.makeText(MainActivity.this, "Service is still running", Toast.LENGTH_SHORT).show();
            mStopButton.setVisibility(View.VISIBLE);
            mStopButton.setEnabled(true);
        }else{
            if(isMyServiceRunning(StopFetchAddressIntentService.class)){
                stopService(stopFetchIntent);
            }
            mStopButton.setVisibility(View.INVISIBLE);
            mStopButton.setEnabled(false);
        }

        mMailadres.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    hideKeyboard(v);
                }
            }
        });

        updateUIWidgets();
        refreshItemsFromTable();

    }

    private void hideKeyboard(View view) {
        InputMethodManager inputMethodManager =(InputMethodManager)getSystemService(Activity.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }


    private boolean isOnline() {
        ConnectivityManager cm =  (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        return cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isConnectedOrConnecting();
    }

    private void initAzureMSConnection(){
        createAzureMobileServiceClients();
        authenticateOnAzure();
        mRidesTable = amsDBClient.getTable(Ride.class);
    }

    private synchronized void createLocationRequest() {
        if(mLocationRequest==null){
            mLocationRequest = new LocationRequest();
            mLocationRequest.setInterval(10000);
            mLocationRequest.setFastestInterval(5000);
            mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        }
    }

    private synchronized void buildGoogleApiClient() {
        if(mGoogleApiClient==null){
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }
    }


    /**
     * Updates fields based on data stored in the bundle.
     */
    private void updateValuesFromBundle(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            if (savedInstanceState.keySet().contains(ADDRESS_REQUESTED_KEY)) {
                mStartAddressRequested = savedInstanceState.getBoolean(ADDRESS_REQUESTED_KEY);
                mStopAddressRequested = savedInstanceState.getBoolean(ADDRESS_REQUESTED_KEY);
            }
            if (savedInstanceState.keySet().contains(LOCATION_ADDRESS_KEY)) {
                mAddressOutput = savedInstanceState.getString(LOCATION_ADDRESS_KEY);
                mLocationAddressTextView.setText(mAddressOutput);
            }
        }
    }

    private void createAzureMobileServiceClients(){
        try {
            amsDBClient = new MobileServiceClient(Constants.AZURE_DBSERVICE_URL, this);
        } catch (MalformedURLException e) {
            Log.e(TAG, "Exception reaching azure mobile app on "+Constants.AZURE_DBSERVICE_URL+", exception: "+e.getCause());
        }

        amsDBClient.setAndroidHttpClientFactory(new OkHttpClientFactory() {
            @Override
            public OkHttpClient createOkHttpClient() {
                OkHttpClient client = new OkHttpClient();
                client.setReadTimeout(20, TimeUnit.SECONDS);
                client.setWriteTimeout(20, TimeUnit.SECONDS);
                return client;
            }
        });

    }


    private void authenticateOnAzure() {
        ListenableFuture<MobileServiceUser> mLogin = amsDBClient.login(MobileServiceAuthenticationProvider.WindowsAzureActiveDirectory);

        Futures.addCallback(mLogin, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable exc) {
                Toast.makeText(MainActivity.this, "Failure at authentication of service", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onSuccess(MobileServiceUser user) {
            }
        });
    }

    public void sendmailButtonHandler(View view) {
        if(!isOnline()){
            Toast.makeText(MainActivity.this, Constants.MSG_NO_INTERNET, Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "";
        try{
            String mailAdress = mMailadres.getText().toString();
            if(!isMailAddresValid(mailAdress)){
                Toast.makeText(MainActivity.this, "Invalid email address entered", Toast.LENGTH_SHORT).show();
                return;
            }
            url = Constants.AZURE_EXCELSERVICE_URL+"/sendExcel/"+mailAdress;
            new GetHttpServiceTask().execute(url);
        }catch (Exception ex){
            Log.e(TAG, "Exception calling: "+url+" ,caused by: "+ex.getCause());
            Toast.makeText(MainActivity.this, "Exception calling excel service: "+ex.getMessage(), Toast.LENGTH_SHORT).show();

        }


    }

    private boolean isMailAddresValid(String mailAddress){
        boolean result = false;
        try {
            String expression = "^[\\w\\.-]+@([\\w\\-]+\\.)+[A-Z]{2,4}$";
            CharSequence inputStr = mailAddress;

            Pattern pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(inputStr);
            if (matcher.matches()) {
                result = true;
            }
        } catch(Exception e){ //catch (AddressException e) {
            Log.e(TAG, "invalid email address "+mailAddress+", exception: "+e.getCause());
        }
        return result;
    }

    public void stopButtonHandler(View view){

        if(!isOnline()){
            Toast.makeText(MainActivity.this, Constants.MSG_NO_INTERNET, Toast.LENGTH_SHORT).show();
            return;
        }

        executeStopFetchIntentService();
        mStartAddressRequested = true;
        mStopAddressRequested = true;

        mStartButton.setVisibility(View.VISIBLE);
        mStartButton.setEnabled(true);

        mStopButton.setVisibility(View.INVISIBLE);
        mStopButton.setEnabled(false);

        updateUIWidgets();

    }

    public void startButtonHandler(View view) {

        if(!isOnline()){
            Toast.makeText(MainActivity.this, Constants.MSG_NO_INTERNET , Toast.LENGTH_SHORT).show();
            return;
        }

        executeStartFetchIntentService();
        mStartAddressRequested = true;
        mStopAddressRequested = false;

        mStartButton.setVisibility(View.INVISIBLE);
        mStartButton.setEnabled(false);

        mStopButton.setVisibility(View.VISIBLE);
        mStopButton.setEnabled(true);
        adapter.clear();
        updateUIWidgets();

    }

    private void refreshItemsFromTable() {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {

                try {
                    //  Show last 3 days
                    Calendar cal = GregorianCalendar.getInstance();
                    if(cal.DAY_OF_YEAR>3)
                        cal.set( Calendar.DAY_OF_YEAR, -3);
                    final List<Ride> rides = mRidesTable.where().field("updatedAt").gt( cal.getTime()).orderBy("updatedAt", QueryOrder.Descending).execute().get();
                    final DateFormat dateFormat = new SimpleDateFormat("MM/dd HH:mm");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.clear();
                            for (Ride ride : rides) {
                                //arrayList.add(dateFormat.format(ride.getStarted_at())+" : "+ride.getStartAddress()+","+ride.getKilometers());
                                arrayList.add(dateFormat.format(ride.getStopped_at())+" : "+ride.getStartAddress()+","+ride.getKilometers());
                                //arrayList.add(ride.getStartAddress()+","+ride.getKilometers());
                            }
                            adapter.notifyDataSetChanged();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Exception populating ride list from database, exception: "+e.getCause());
                }

                return null;
            }
        };
        runAsyncTask(task);
    }


    private AsyncTask<Void, Void, Void> runAsyncTask(AsyncTask<Void, Void, Void> task) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            return task.execute();
        }
    }


    @Override
    protected void onStart() {
        super.onStart();

        Action viewAction = Action.newAction(
                Action.TYPE_VIEW,
                "Main Page",
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://nl.microsoft.bizmilesapp.azuredemo/http/host/path")
        );
        AppIndex.AppIndexApi.start(client, viewAction);
        client.connect();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW,
                "Main Page",
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://nl.microsoft.bizmilesapp.azuredemo/http/host/path")
        );
        AppIndex.AppIndexApi.end(client, viewAction);
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        client.disconnect();
        //System.exit(0);
    }

    /**
     * Runs when a GoogleApiClient object successfully connects.
     */
    @Override
    public void onConnected(Bundle connectionHint) {

    }



    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }


    @Override
    public void onConnectionSuspended(int cause) {
        Log.i(TAG, "Connection suspended");
        mGoogleApiClient.connect();
    }


    /**
     * Toggles the visibility of the progress bar. Enables or disables the Fetch Address button.
     */
    private void updateUIWidgets() {
        mProgressBar.setVisibility(ProgressBar.GONE);

        if (mStartAddressRequested) {
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(true);
        }
        if(mStopAddressRequested){
            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
        }
    }


    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Save whether the address has been requested.
        savedInstanceState.putBoolean(ADDRESS_REQUESTED_KEY, mStartAddressRequested);

        // Save the address string.
        savedInstanceState.putString(LOCATION_ADDRESS_KEY, mAddressOutput);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastLocation = location;
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }


    protected void executeStartFetchIntentService() {
        if(!(mGoogleApiClient.isConnected())){
            Toast.makeText(MainActivity.this, "not connnected", Toast.LENGTH_SHORT).show();
            return;
        }else{
            startFetchintent = new Intent(this, StartFetchAddressIntentService.class);
            startFetchintent.putExtra(Constants.RECEIVER, mStartResultReceiver);
            startFetchintent.putExtra(Constants.LOCATION_DATA_EXTRA, mLastLocation);
            startFetchintent.putExtra(Constants.START_TIME,  new Long(GregorianCalendar.getInstance().getTimeInMillis()).toString());

            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
            mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            startFetchintent.putExtra(Constants.START_LOCATION, mLastLocation);
            startService(startFetchintent);
        }
    }


    @SuppressLint("ParcelCreator")
    class BizMilesStartReceiver extends ResultReceiver {
        public BizMilesStartReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {

            // Display the address string or an error message sent from the intent service.
            mAddressOutput = resultData.getString(Constants.RESULT_DATA_KEY);

            startFetchintent.putExtra(Constants.START_LOCATION_ADDR,mAddressOutput);
            mLocationAddressTextView.setText(mAddressOutput);


            // Reset. Enable the Fetch Address button and stop showing the progress bar
            mStartAddressRequested = true;
            updateUIWidgets();
        }
    }


    protected void executeStopFetchIntentService() {
        if(!(mGoogleApiClient.isConnected())){
            Toast.makeText(MainActivity.this, "not connnected", Toast.LENGTH_SHORT).show();
            return;
        }else{
            stopFetchIntent = new Intent(this, StopFetchAddressIntentService.class);
            stopFetchIntent.putExtra(Constants.RECEIVER, mStopResultReceiver);
            stopFetchIntent.putExtra(Constants.LOCATION_DATA_EXTRA, mLastLocation);

            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
            mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            stopFetchIntent.putExtra(Constants.STOP_LOCATION, mLastLocation);

            startService(stopFetchIntent);
        }
    }

    @SuppressLint("ParcelCreator")
    class BizMilesStopReceiver extends ResultReceiver {
        public BizMilesStopReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mAddressOutput = resultData.getString(Constants.RESULT_DATA_KEY);
            Toast.makeText(MainActivity.this, "Stopped ride at: "+mAddressOutput, Toast.LENGTH_SHORT).show();
            // Display the address string or an error message sent from the intent service.
            Location startLocation = startFetchintent.getParcelableExtra(Constants.START_LOCATION);
            Location stopLocation = stopFetchIntent.getParcelableExtra(Constants.STOP_LOCATION);

            float distance = 0;
            DecimalFormat df = new DecimalFormat("#.#");
            df.setRoundingMode(RoundingMode.CEILING);
            boolean succeeded = false;
            if(startLocation !=null && stopLocation !=null ){
                //distance = Float.parseFloat(df.format(startLocation.distanceTo(stopLocation)));
                distance = startLocation.distanceTo(stopLocation)/1000;
                Toast.makeText(MainActivity.this, "Calculated distance: "+distance, Toast.LENGTH_SHORT).show();
                //  Inserting into database, only if distance >0;
                if(distance>0){
                    try{

                        if(amsDBClient==null){
                            initAzureMSConnection();
                        }

                        Ride ride = new Ride();
                        ride.setStartAddress(startFetchintent.getStringExtra(Constants.START_LOCATION_ADDR));
                        long startTime = new Long(startFetchintent.getStringExtra(Constants.START_TIME)).longValue();
                        ride.setStarted_at(new Date(startTime));

                        ride.setKilometers(distance);
                        ride.setStopAddress(mAddressOutput);
                        ride.setStopped_at(GregorianCalendar.getInstance().getTime());
                        mRidesTable.insert(ride);
                        succeeded = true;
                    }catch(Exception ex){
                        Toast.makeText(MainActivity.this, "DB Exception while trying to persist ride", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "DB Exception while trying to persist ride : "+ex.getCause());

                    }
                }
            }
            if(succeeded){
                mStopAddressRequested = true;
                //setLocation(ActionTypes.CLEAR);
                refreshItemsFromTable();
                updateUIWidgets();

                //  Always cleanup old intents
                stopService(startFetchintent);
                stopService(stopFetchIntent);
            }else{
                Toast.makeText(MainActivity.this, "Something went wrong saving the ride, km > 0 ?...", Toast.LENGTH_SHORT).show();
            }

        }
    }


    private class GetHttpServiceTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... urls) {

            // params comes from the execute() call: params[0] is the url.
            try {
                if(amsDBClient==null || amsDBClient.getCurrentUser()==null){
                    initAzureMSConnection();
                }
                return getUrl(urls[0]);
            } catch (IOException e) {
                return "Unable to retrieve web page. URL may be invalid.";
            }
        }
        // onPostExecute displays the results of the AsyncTask.
        @Override
        protected void onPostExecute(String result) {
            //textView.setText(result);
        }

        private String getUrl(String myurl) throws IOException {
            int response = 0;
            HttpURLConnection conn = null;

            try {
                URL url = new URL(myurl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(10000 /* milliseconds */);
                conn.setConnectTimeout(15000 /* milliseconds */);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                HttpURLConnection.setFollowRedirects(true);

                conn.connect();
                response = conn.getResponseCode();
                //  If security is enabled on Azure service, this fix is needed
                if(response == HttpURLConnection.HTTP_MOVED_TEMP || response == HttpURLConnection.HTTP_MOVED_PERM){
                    String stringUrl = conn.getHeaderField("Location");
                    conn = (HttpURLConnection) new URL(stringUrl).openConnection();
                    conn.connect();
                    response = conn.getResponseCode();
                }
            } finally {
                Toast.makeText(MainActivity.this, "Called service, response was: "+response, Toast.LENGTH_SHORT).show();
            }
            //  Not interested in http result stuff, just the resultcode (returning nothing)
            return new Integer(response).toString();
        }

    }


}
