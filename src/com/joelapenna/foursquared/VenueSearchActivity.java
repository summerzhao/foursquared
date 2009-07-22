/**
 * Copyright 2009 Joe LaPenna
 */

package com.joelapenna.foursquared;

import com.joelapenna.foursquare.Foursquare;
import com.joelapenna.foursquare.error.FoursquareError;
import com.joelapenna.foursquare.error.FoursquareParseException;
import com.joelapenna.foursquare.types.Group;
import com.joelapenna.foursquare.types.Venue;
import com.joelapenna.foursquared.test.FoursquaredTest;
import com.joelapenna.foursquared.util.SeparatedListAdapter;

import android.app.ListActivity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import java.io.IOException;
import java.util.Date;

/**
 * @author Joe LaPenna (joe@joelapenna.com)
 */
public class VenueSearchActivity extends ListActivity {
    private static final String TAG = "VenueSearchActivity";
    private static final boolean DEBUG = Foursquared.DEBUG;

    private static final int MENU_SEARCH = 0;
    private static final int MENU_REFRESH = 1;
    private static final int MENU_NEARBY = 2;

    private static final String QUERY_NEARBY = null;

    private static final long LOCATION_UPDATE_MIN_TIME = 1000 * 60;
    private static final long LOCATION_UPDATE_MIN_DISTANCE = 100;
    private static final long MAX_LOCATION_UPDATE_DELTA_THRESHOLD = 1000 * 60 * 5;

    private SearchAsyncTask mSearchTask;
    private LocationManager mLocationManager;
    private BestLocationListener mLocationListener;
    private Location mLocation;

    private String mQuery;

    private TextView mEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) Log.d(TAG, "onCreate");

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.venue_search_activity);

        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        mLocationListener = new BestLocationListener();
        mLocation = ((Foursquared)getApplication()).getLocation();

        setListAdapter(new SeparatedListAdapter(this));
        getListView().setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Venue venue = (Venue)parent.getAdapter().getItem(position);
                fireVenueActivityIntent(venue);
            }
        });

        mEmpty = (TextView)findViewById(android.R.id.empty);

        if (getLastNonConfigurationInstance() != null) {
            if (DEBUG) Log.d(TAG, "Restoring configuration.");
            mQuery = (String)getLastNonConfigurationInstance();
            startQuery(mQuery);
        } else {
            if (DEBUG) Log.d(TAG, "Running new intent.");
            onNewIntent(getIntent());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, R.string.search_label) // More stuff.
                .setIcon(android.R.drawable.ic_menu_search);
        menu.add(Menu.NONE, MENU_NEARBY, Menu.NONE, R.string.nearby_label) // More stuff.
                .setIcon(android.R.drawable.ic_menu_compass);
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.refresh_label) // More stuff.
                .setIcon(android.R.drawable.ic_search_category_default);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SEARCH:
                onSearchRequested();
                return true;
            case MENU_NEARBY:
                startQuery(null);
                return true;
            case MENU_REFRESH:
                startQuery(mQuery);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "New Intent: " + intent);
        if (intent == null) {
            if (DEBUG) Log.d(TAG, "No intent to search, querying default.");
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            if (DEBUG) Log.d(TAG, "onNewIntent received search intent");
        }
        startQuery(intent.getStringExtra(SearchManager.QUERY));
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mQuery;
    }

    @Override
    public void onStart() {
        super.onStart();
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_MIN_TIME, LOCATION_UPDATE_MIN_DISTANCE, mLocationListener);
        mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_MIN_TIME, LOCATION_UPDATE_MIN_DISTANCE, mLocationListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        mLocationManager.removeUpdates(mLocationListener);
        if (mSearchTask != null) {
            mSearchTask.cancel(true);
        }
    }

    void testStuff() {
        Group groups = new Group();
        groups.setType("TLG");
        groups.add(FoursquaredTest.createVenueGroup("Group A"));
        groups.add(FoursquaredTest.createVenueGroup("Group B"));
        groups.add(FoursquaredTest.createVenueGroup("Group C"));
        putGroupsInAdapter(groups);
    }

    void startQuery(String query) {
        if (DEBUG) Log.d(TAG, "sendQuery()");
        mQuery = query;

        // If a task is already running, don't start a new one.
        if (mSearchTask != null && mSearchTask.getStatus() != AsyncTask.Status.FINISHED) {
            if (DEBUG) Log.d(TAG, "Query already running attempting to cancel: " + mSearchTask);
            if (!mSearchTask.cancel(true) && !mSearchTask.isCancelled()) {
                if (DEBUG) Log.d(TAG, "Unable to cancel search? Notifying the user.");
                Toast.makeText(this, "A search is already in progress.", Toast.LENGTH_SHORT);
                return;
            }
        }
        mSearchTask = (SearchAsyncTask)new SearchAsyncTask().execute();
    }

    void fireVenueActivityIntent(Venue venue) {
        if (DEBUG) Log.d(TAG, "firing venue activity for venue");
        Intent intent = new Intent(VenueSearchActivity.this, VenueActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("venue", venue);
        startActivity(intent);
    }

    private void putGroupsInAdapter(Group groups) {
        if (groups == null) {
            Toast.makeText(getApplicationContext(), "Could not complete search!",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        SeparatedListAdapter mainAdapter = (SeparatedListAdapter)getListAdapter();
        mainAdapter.clear();
        int groupCount = groups.size();
        for (int groupsIndex = 0; groupsIndex < groupCount; groupsIndex++) {
            Group group = (Group)groups.get(groupsIndex);
            if (group.size() > 0) {
                VenueListAdapter groupAdapter = new VenueListAdapter(this, group);
                if (DEBUG) Log.d(TAG, "Adding Section: " + group.getType());
                mainAdapter.addSection(group.getType(), groupAdapter);
            }
        }
        mainAdapter.notifyDataSetInvalidated();
    }

    private class SearchAsyncTask extends AsyncTask<Void, Void, Group> {

        @Override
        public void onPreExecute() {
            if (DEBUG) Log.d(TAG, "SearchTask: onPreExecute()");
            setProgressBarIndeterminateVisibility(true);
            if (mQuery == QUERY_NEARBY) {
                setTitle("Searching Nearby - Foursquared");
            } else {
                setTitle("Searching \"" + mQuery + "\" - Foursquared");
            }
        }

        @Override
        public Group doInBackground(Void... params) {
            try {
                Location location = mLocation;
                Foursquare foursquare = ((Foursquared)getApplication()).getFoursquare();
                if (location == null) {
                    return foursquare.venues(mQuery, null, null, 10, 1);
                } else {
                    return foursquare.venues(mQuery, String.valueOf(location.getLatitude()), String
                            .valueOf(location.getLongitude()), 10, 1);
                }
            } catch (FoursquareError e) {
                // TODO Auto-generated catch block
                if (DEBUG) Log.d(TAG, "FoursquareError", e);
            } catch (FoursquareParseException e) {
                // TODO Auto-generated catch block
                if (DEBUG) Log.d(TAG, "FoursquareParseException", e);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                if (DEBUG) Log.d(TAG, "IOException", e);
            }
            return null;
        }

        @Override
        public void onPostExecute(Group groups) {
            try {
                putGroupsInAdapter(groups);
            } finally {
                setProgressBarIndeterminateVisibility(false);
                if (mQuery == QUERY_NEARBY) {
                    setTitle("Nearby - Foursquared");
                } else {
                    setTitle(mQuery + " - Foursquared");
                }
                if (getListAdapter().getCount() <= 0) {
                    mEmpty.setText("No results found! Try another search!");
                }
            }
        }
    }

    public class BestLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            if (DEBUG) Log.d(TAG, "onLocationChanged: " + location.getProvider());
            if (mLocation == null) {
                if (DEBUG) Log.d(TAG, "No previous location. using new: " + location);
                mLocation = location;
                return;
            }

            // If we've decided to use the new location.
            if (updateLocation(location)) {

            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            // do nothing.
        }

        @Override
        public void onProviderEnabled(String provider) {
            // do nothing.
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // do nothing.
        }

        private boolean updateLocation(Location location) {
            Location lastLocation = mLocation;

            long now = new Date().getTime();
            long locationUpdateDelta = now - location.getTime();
            long lastLocationUpdateDelta = now - lastLocation.getTime();

            boolean locationIsMostRecent = locationUpdateDelta <= lastLocationUpdateDelta;

            boolean accuracyComparable = location.hasAccuracy() && lastLocation.hasAccuracy();
            boolean locationIsMoreAccurate = location.getAccuracy() <= lastLocation.getAccuracy();

            boolean locationIsInTimeThreshold = locationUpdateDelta <= MAX_LOCATION_UPDATE_DELTA_THRESHOLD;
            boolean lastLocationIsInTimeThreshold = lastLocationUpdateDelta <= MAX_LOCATION_UPDATE_DELTA_THRESHOLD;

            if (accuracyComparable && locationIsMoreAccurate && locationIsMostRecent) {
                if (DEBUG) Log.d(TAG, "New +Accuracy, +Time, using new: " + location);
                mLocation = location;
                return true;
            } else if (accuracyComparable && locationIsMoreAccurate && !locationIsInTimeThreshold) {
                if (DEBUG) Log.d(TAG, "New +Accuracy, -Time. Using old:" + lastLocation);
                return false;
            } else if (accuracyComparable && !locationIsMoreAccurate && !locationIsInTimeThreshold) {
                if (DEBUG) Log.d(TAG, "New -Accuracy -Time. Using old: " + lastLocation);
                return false;
            } else if (locationIsMostRecent) {
                if (DEBUG) Log.d(TAG, "New ?Accuracy, +Time. Using new: " + location);
                mLocation = location;
                return true;
            } else if (!lastLocationIsInTimeThreshold) {
                if (DEBUG) Log.d(TAG, "Old location too old. Using new: " + location);
                mLocation = location;
                return true;
            } else {
                if (DEBUG) Log.d(TAG, "Poor comparitive data. Using old: " + lastLocation);
                return false;
            }
        }
    }
}
