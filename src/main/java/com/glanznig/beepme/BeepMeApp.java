/*
This file is part of BeepMe.

BeepMe is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

BeepMe is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with BeepMe. If not, see <http://www.gnu.org/licenses/>.

Copyright 2012-2014 Michael Glanznig
http://beepme.yourexp.at
*/

package com.glanznig.beepme;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import com.glanznig.beepme.data.Beep;
import com.glanznig.beepme.data.Moment;
import com.glanznig.beepme.data.Timer;
import com.glanznig.beepme.data.Uptime;
import com.glanznig.beepme.data.util.PreferenceHandler;
import com.glanznig.beepme.data.db.BeepTable;
import com.glanznig.beepme.data.db.MomentTable;
import com.glanznig.beepme.data.db.StorageHandler;
import com.glanznig.beepme.data.db.UptimeTable;
import com.glanznig.beepme.helper.PhotoUtils;
import com.glanznig.beepme.view.BeepActivity;
import com.glanznig.beepme.view.ExportActivity;
import com.glanznig.beepme.view.MainActivity;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;

public class BeepMeApp extends Application { //implements SharedPreferences.OnSharedPreferenceChangeListener {
	
	private PreferenceHandler preferences = null;
	private Timer timerProfile;
	private BeepMeApp.CallStateListener callStateListener;
	
	private static final int ALARM_INTENT_ID = 5332;
	private static final int NOTIFICATION_ID = 1283;
	private static final String TAG = "BeeperApp";
	
	public static final int BEEPER_INACTIVE = 0;
	public static final int BEEPER_ACTIVE = 1;
	public static final int BEEPER_INACTIVE_AFTER_CALL = 2;
	
	public PreferenceHandler getPreferences() {
		if (preferences == null) {
			preferences = new PreferenceHandler(this.getApplicationContext());
			//preferences.registerOnPreferenceChangeListener(BeeperApp.this);
		}
		
		return preferences;
	}
	
	public boolean isBeeperActive() {
		int active = getPreferences().getBeeperActive();
		if (active == BEEPER_ACTIVE) {
			return true;
		}
		
		return false;
	}
	
	public void setBeeperActive(int active) {
		getPreferences().setBeeperActive(active);
		
		if (active == BEEPER_ACTIVE) {
            Uptime uptime = new Uptime();
            uptime.setStart(Calendar.getInstance().getTime());
            uptime = new UptimeTable(this.getApplicationContext()).addUptime(uptime);
			getPreferences().setUptimeId(uptime.getUid());
			createNotification();
		}
		else {
			long uptimeId = getPreferences().getUptimeId();
			
			if (uptimeId != 0L) {
                UptimeTable uptimeTable = new UptimeTable(this.getApplicationContext());
                Uptime uptime = uptimeTable.getUptime(uptimeId);
                if (uptime != null) {
                    uptime.setEnd(Calendar.getInstance().getTime());
                    uptimeTable.updateUptime(uptime);
                    getPreferences().setUptimeId(0L);
                    NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.cancel(TAG, NOTIFICATION_ID);
                }
			}
		}
	}
	
	private void createNotification() {
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
		
		if (this.getPreferences().isTestMode()) {
			notificationBuilder.setSmallIcon(R.drawable.ic_stat_notify_testmode);
			notificationBuilder.setContentTitle(getString(R.string.notify_title_testmode));
		}
		else {
			notificationBuilder.setSmallIcon(R.drawable.ic_stat_notify);
			notificationBuilder.setContentTitle(getString(R.string.notify_title));	
		}
		notificationBuilder.setContentText(getString(R.string.notify_content));
		//set as ongoing, so it cannot be cleared
		notificationBuilder.setOngoing(true);
		// Creates an explicit intent for an Activity in your app
		Intent resultIntent = new Intent(this, MainActivity.class);

		// The stack builder object will contain an artificial back stack for the
		// started Activity.
		// This ensures that navigating backward from the Activity leads out of
		// your application to the Home screen.
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
		// Adds the back stack for the Intent (but not the Intent itself)
		stackBuilder.addParentStack(MainActivity.class);
		// Adds the Intent that starts the Activity to the top of the stack
		stackBuilder.addNextIntent(resultIntent);
		PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
		notificationBuilder.setContentIntent(resultPendingIntent);
		NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
		// notification_id allows you to update the notification later on.
		manager.notify(TAG, NOTIFICATION_ID, notificationBuilder.build());
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		getPreferences();

        onAppUpdate(getPreferences().getAppVersion());

        // save thumbnail sizes
        DisplayMetrics metrics = this.getResources().getDisplayMetrics();
        int screenDpWidth = (int)(metrics.widthPixels / metrics.density + 0.5f);
        int[] sizes = {48, 64, screenDpWidth};
        getPreferences().setThumbnailSizes(sizes);
		
		// listen to call events
		if (callStateListener == null) {
			callStateListener = new CallStateListener(BeepMeApp.this);
		}
		TelephonyManager telManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
		telManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE);
		
		//set export running to false
		getPreferences().setExportRunningSince(0L);
		
		UptimeTable uptimeTbl = new UptimeTable(this.getApplicationContext());
		
		if (isBeeperActive()) {
			long scheduledBeepId = getPreferences().getScheduledBeepId();
			//is there a scheduled beep, if no, create one, if yes and it is expired, create a new one
			if (scheduledBeepId != 0L) {
				BeepTable beepTable = new BeepTable(this.getApplicationContext());
                Beep beep = beepTable.getBeep(scheduledBeepId);
				if (beep.getStatus() != Beep.BeepStatus.RECEIVED && beep.isOverdue()) {
					updateBeep(Beep.BeepStatus.EXPIRED);
					setTimer();
				}
			}
			else {
				setTimer();
			}
			
			//is there a notification, if no, create one
			//cannot check if there is a notification or not, so call create, it will be replaced
			createNotification();
			
			//is there a open uptime interval, if no, create one
			long uptimeId = getPreferences().getUptimeId();
			if (uptimeId == 0L) {
                Uptime uptime = new Uptime();
                uptime.setStart(Calendar.getInstance().getTime());
                uptime = new UptimeTable(this.getApplicationContext()).addUptime(uptime);
                getPreferences().setUptimeId(uptime.getUid());
			}
		}
		else {
			long scheduledBeepId = getPreferences().getScheduledBeepId();
			//is there a scheduled beep, if yes, cancel it
			if (scheduledBeepId != 0L) {
				updateBeep(Beep.BeepStatus.CANCELLED);
			}
			
			//cancel notifications
			NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			manager.cancel(TAG, NOTIFICATION_ID);
            manager.cancel(TAG, ExportActivity.EXPORT_RUNNING_NOTIFICATION);
			
			//is there a open uptime interval, if yes, end it
			long uptimeId = getPreferences().getUptimeId();
			
			if (uptimeId != 0L) {
                UptimeTable uptimeTable = new UptimeTable(this.getApplicationContext());
                Uptime uptime = uptimeTable.getUptime(uptimeId);
                if (uptime != null) {
                    uptime.setEnd(Calendar.getInstance().getTime());
                    uptimeTable.updateUptime(uptime);
                    getPreferences().setUptimeId(0L);
                }
			}
		}
	}
	
	public void setTimer() {
		if (isBeeperActive()) {
			Calendar alarmTime = Calendar.getInstance();
			Calendar alarmTimeUTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			
			long timer = timerProfile.getTimer(this.getApplicationContext());
	        alarmTime.add(Calendar.SECOND, (int)timer);
	        //Log.i(TAG, "alarm in " + timer + " seconds.");
	        alarmTimeUTC.add(Calendar.SECOND, (int)timer);

            Beep beep = new Beep();
            beep.setCreated(Calendar.getInstance().getTime());
            beep.setStatus(Beep.BeepStatus.ACTIVE);
            beep.setUptimeUid(getPreferences().getUptimeId());
            beep = new BeepTable(this.getApplicationContext()).addBeep(beep);
            getPreferences().setScheduledBeepId(beep.getUid());
	        
	        Intent intent = new Intent(this, BeepActivity.class);
	        PendingIntent alarmIntent = PendingIntent.getActivity(this, ALARM_INTENT_ID, intent,
	        		PendingIntent.FLAG_CANCEL_CURRENT);
	        AlarmManager manager = (AlarmManager)getSystemService(Activity.ALARM_SERVICE);
	        manager.set(AlarmManager.RTC_WAKEUP, alarmTimeUTC.getTimeInMillis(), alarmIntent);
		}
	}
	
	public void updateBeep(Beep.BeepStatus status) {
		if (status != Beep.BeepStatus.ACTIVE || status != Beep.BeepStatus.RECEIVED) {
			Intent intent = new Intent(this, BeepActivity.class);
	        PendingIntent alarmIntent = PendingIntent.getActivity(this, ALARM_INTENT_ID, intent,
	        		PendingIntent.FLAG_CANCEL_CURRENT);
			alarmIntent.cancel();
		}

        Beep beep = new Beep(getPreferences().getScheduledBeepId());
        beep.setStatus(status);
        beep.setUpdated(Calendar.getInstance().getTime());
        new BeepTable(this.getApplicationContext()).updateBeep(beep);

		if (status != Beep.BeepStatus.ACTIVE) {
			getPreferences().setScheduledBeepId(0L);
		}
	}
	
	public void beep() {
		if (isBeeperActive()) {
			Intent beep = new Intent(BeepMeApp.this, BeepActivity.class);
			beep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(beep);
		}
	}

    private void onAppUpdate(int oldVersion) {
        try {
            PackageInfo packageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            int newVersion = packageInfo.versionCode;

            //if (oldVersion > 0 && oldVersion != newVersion) {
            // if should be above line, change in future versions
            if (oldVersion < newVersion) {
                for (int mVers = oldVersion; mVers < newVersion; mVers++) {
                    switch (mVers) {
                        case 16:
                            String dbName;
                            String picDirName;
                            if (getPreferences().isTestMode()) {
                                dbName = StorageHandler.getTestModeDatabaseName();
                                picDirName = PhotoUtils.TEST_MODE_DIR;
                            }
                            else {
                                dbName = StorageHandler.getProductionDatabaseName();
                                picDirName = PhotoUtils.NORMAL_MODE_DIR;
                            }

                            // rename the database
                            if (!dbName.equals(StorageHandler.DB_OLD_NAME)) {
                                File oldDb = getDatabasePath(StorageHandler.DB_OLD_NAME);
                                File newDb = new File(oldDb.getParentFile(), dbName);
                                oldDb.renameTo(newDb);
                            }

                            // move pictures
                            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                                File oldDir = this.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                                File newDir = new File(oldDir.getAbsolutePath(), picDirName);

                                if (!newDir.exists()) {
                                    newDir.mkdirs();
                                }

                                File[] picFiles = oldDir.listFiles(new FilenameFilter() {
                                    public boolean accept(File dir, String name) {
                                        return name.toLowerCase().endsWith(".jpg");
                                    }
                                });

                                for (int i = 0; i < picFiles.length; i++) {
                                    picFiles[i].renameTo(new File(newDir, picFiles[i].getName()));
                                }

                                MomentTable st = new MomentTable(this);
                                List<Moment> list = st.getSamples();
                                for (int i = 0; i < list.size(); i++) {
                                    Moment s = list.get(i);
                                    String uri = s.getPhotoUri();

                                    if (uri != null) {
                                        File pic = new File(uri);
                                        s.setPhotoUri(pic.getParent() + File.separator + picDirName + File.separator + pic.getName());
                                        st.editSample(s);
                                    }
                                }
                            }

                            break;
                    }
                }
            } // else we would have a new install or the data had been deleted

            getPreferences().setAppVersion(newVersion);
        }
        catch(PackageManager.NameNotFoundException nnfe) {}
    }
	
	private static class CallStateListener extends PhoneStateListener {
		
		private WeakReference<BeepMeApp> appRef;
		
		public CallStateListener(BeepMeApp app) {
			appRef = new WeakReference<BeepMeApp>(app);
		}
		
		@Override
		public void onCallStateChanged(int state, String incomingNumber) {
			if (appRef != null && appRef.get() != null) {
				BeepMeApp app = appRef.get();
				switch (state) {
                    case TelephonyManager.CALL_STATE_IDLE:
                        app.getPreferences().setCall(false);
                        // beeper was paused by call, reactivate it
                        if (app.getPreferences().getBeeperActive() == BeepMeApp.BEEPER_INACTIVE_AFTER_CALL) {
                            app.setBeeperActive(BeepMeApp.BEEPER_ACTIVE);
                        }
                        break;
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        app.getPreferences().setCall(true);
                        break;
                    case TelephonyManager.CALL_STATE_RINGING:
                        app.getPreferences().setCall(true);
                        break;
				}
			}
		}
	}
}

