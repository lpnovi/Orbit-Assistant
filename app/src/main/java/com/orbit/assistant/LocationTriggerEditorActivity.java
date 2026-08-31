package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Orbit-themed editor for arrive/leave Routine triggers. */
public final class LocationTriggerEditorActivity extends Activity {
    public static final String EXTRA_ROUTINE_ID = "routine_id";
    public static final String EXTRA_TRIGGER_ID = "trigger_id";

    private static final int REQ_FINE_LOCATION = 881;
    private static final int REQ_BACKGROUND_LOCATION = 882;

    private String routineId;
    private String triggerId;
    private RoutineStore.Routine routine;
    private RoutineTriggerStore.Trigger original;

    private String transition = RoutineTriggerStore.LOCATION_ENTER;
    private float radiusMeters = 200f;
    private boolean enabled = true;
    private boolean dirty;
    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;
    private boolean captureAfterPermission;

    private EditText nameField;
    private EditText latitudeField;
    private EditText longitudeField;
    private Button transitionButton;
    private Button savedPlaceButton;
    private Button radiusButton;
    private Button currentLocationButton;
    private CheckBox enabledBox;
    private LinearLayout accessCard;
    private String selectedSavedPlaceId = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        routineId = getIntent().getStringExtra(EXTRA_ROUTINE_ID);
        triggerId = getIntent().getStringExtra(EXTRA_TRIGGER_ID);
        routine = RoutineStore.findById(this, routineId);
        original = RoutineTriggerStore.findById(this, triggerId);
        if (routine == null || (triggerId != null && (original == null ||
                !RoutineTriggerStore.TYPE_LOCATION.equals(original.type)))) {
            finish();
            return;
        }
        if (original != null) {
            transition = original.locationTransition;
            radiusMeters = original.radiusMeters;
            enabled = original.enabled;
        }
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        refreshFields();
        refreshAccessCard();
        // Offered only while there is nothing to lose. The discard confirmation is unchanged and is
        // still what Back reaches once there is, so the page never slides away on a decision the
        // user has not made yet.
        navigation = OrbitPredictiveBack.install(this, new OrbitPredictiveBack.Screen() {
            @Override public boolean canNavigate() { return !dirty; }
            @Override public void navigateBack() { onBackPressed(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(LocationTriggerEditorActivity.class);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        refreshAccessCard();
        RoutineTriggerScheduler.rescheduleAll(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (dirty) confirmDiscard(); else super.onBackPressed();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshAccessCard();
        if (requestCode == REQ_FINE_LOCATION && RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            if (captureAfterPermission) {
                captureAfterPermission = false;
                captureCurrentLocation();
            }
        }
        if (requestCode == REQ_BACKGROUND_LOCATION) RoutineTriggerScheduler.rescheduleAll(this);
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onBackPressed());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, original == null ? "New location trigger" : "Edit location trigger", 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, routine.name, 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Run this routine when your phone arrives at or leaves an area. Choose a reusable saved place, use your current position, or enter coordinates manually.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.setMargins(2, UiKit.dp(this, 16), 2, UiKit.dp(this, 14));
        page.addView(intro, introLp);

        LinearLayout location = card();
        location.addView(label("WHEN"));
        transitionButton = selectorButton("");
        transitionButton.setOnClickListener(v -> showTransitionMenu());
        location.addView(transitionButton, selectorLp());

        location.addView(label("SAVED PLACE (OPTIONAL)"));
        savedPlaceButton = selectorButton("Choose saved place");
        savedPlaceButton.setOnClickListener(v -> showSavedPlaceMenu());
        location.addView(savedPlaceButton, selectorLp());
        TextView savedHelp = UiKit.text(this,
                "Choose a reusable place from Settings, use your current location, or enter coordinates manually.",
                11, UiKit.MUTED, false);
        savedHelp.setPadding(0, UiKit.dp(this, 6), 0, 0);
        location.addView(savedHelp);

        location.addView(label("LOCATION NAME"));
        nameField = textField("Home, Work, Gym…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setText(original == null ? "" : original.locationName);
        location.addView(nameField, fieldLp());

        currentLocationButton = secondaryButton("Use my current location");
        currentLocationButton.setOnClickListener(v -> captureCurrentLocation());
        LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46));
        currentLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        location.addView(currentLocationButton, currentLp);

        TextView coordinateHelp = UiKit.text(this,
                "Orbit stores only the coordinates and label you save for this trigger. Current location requires precise location access.",
                11, UiKit.MUTED, false);
        coordinateHelp.setPadding(0, UiKit.dp(this, 7), 0, 0);
        location.addView(coordinateHelp);

        LinearLayout coords = new LinearLayout(this);
        coords.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout latBox = new LinearLayout(this);
        latBox.setOrientation(LinearLayout.VERTICAL);
        latBox.addView(label("LATITUDE"));
        latitudeField = numberField(original == null ? "" : coordinate(original.latitude));
        latBox.addView(latitudeField, fieldLp());
        coords.addView(latBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout lonBox = new LinearLayout(this);
        lonBox.setOrientation(LinearLayout.VERTICAL);
        lonBox.addView(label("LONGITUDE"));
        longitudeField = numberField(original == null ? "" : coordinate(original.longitude));
        lonBox.addView(longitudeField, fieldLp());
        LinearLayout.LayoutParams lonLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lonLp.leftMargin = UiKit.dp(this, 9);
        coords.addView(lonBox, lonLp);
        location.addView(coords);

        location.addView(label("RADIUS"));
        radiusButton = selectorButton("");
        radiusButton.setOnClickListener(v -> showRadiusMenu());
        location.addView(radiusButton, selectorLp());

        enabledBox = new CheckBox(this);
        enabledBox.setText("Enabled");
        enabledBox.setTextColor(UiKit.TEXT);
        enabledBox.setTextSize(14);
        enabledBox.setButtonTintList(ColorStateList.valueOf(UiKit.accent(this)));
        enabledBox.setPadding(0, UiKit.dp(this, 10), 0, 0);
        enabledBox.setChecked(enabled);
        enabledBox.setOnCheckedChangeListener((b, checked) -> {
            if (enabled != checked) {
                enabled = checked;
                dirty = true;
            }
        });
        UiKit.pressScale(enabledBox);
        location.addView(enabledBox);
        page.addView(location, cardLp());

        accessCard = card();
        page.addView(accessCard, cardLp());

        LinearLayout behavior = card();
        behavior.addView(UiKit.text(this, "Background behavior", 14, UiKit.TEXT, true));
        TextView body = UiKit.text(this,
                "Location triggers use Android's system proximity monitoring. The routine still follows Orbit's normal automatic-run rules: background-safe steps can finish silently, while steps that require Orbit visible or a confirmation use the existing trigger-alert handoff. Android location boundaries are approximate, so a transition can arrive late and very brief passes may not fire.",
                12, UiKit.MUTED, false);
        body.setPadding(0, UiKit.dp(this, 6), 0, 0);
        behavior.addView(body);
        page.addView(behavior, cardLp());

        Button save = primaryButton(original == null ? "Save location trigger" : "Save changes");
        save.setOnClickListener(v -> save());
        page.addView(save, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 50)));
        return scroll;
    }

    private void refreshFields() {
        if (transitionButton != null) {
            transitionButton.setText(RoutineTriggerStore.LOCATION_EXIT.equals(transition)
                    ? "When I leave" : "When I arrive");
        }
        if (radiusButton != null) radiusButton.setText(RoutineTriggerSchedule.radiusLabel(radiusMeters));
        if (savedPlaceButton != null) {
            SavedPlaceStore.Place selectedPlace = SavedPlaceStore.get(this, selectedSavedPlaceId);
            savedPlaceButton.setText(selectedPlace == null ? "Choose saved place" : selectedPlace.name);
        }
        if (enabledBox != null && enabledBox.isChecked() != enabled) enabledBox.setChecked(enabled);
    }

    private void refreshAccessCard() {
        if (accessCard == null) return;
        accessCard.removeAllViews();
        boolean fine = RoutineLocationTriggerScheduler.hasFineLocation(this);
        boolean background = RoutineLocationTriggerScheduler.hasBackgroundLocation(this);
        boolean locationOn = RoutineLocationTriggerScheduler.isLocationEnabled(this);
        boolean ready = fine && background && locationOn;

        accessCard.addView(UiKit.text(this, ready ? "Location triggers ready" : "Location setup needed",
                14, ready ? UiKit.SUCCESS : UiKit.TEXT, true));
        String detail;
        String button;
        if (!fine) {
            detail = "Allow precise location so Orbit can tell when the phone crosses the saved radius.";
            button = "Allow precise location";
        } else if (!background) {
            String label = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
            detail = "Automatic arrive/leave triggers need background location. In Android's app permission page, set Location to “" + label + "”.";
            button = "Allow background location";
        } else if (!locationOn) {
            detail = "Android location services are currently off. Turn them on for arrive/leave monitoring.";
            button = "Turn on location";
        } else {
            detail = "Precise and background location are available. Enabled location triggers can monitor even while Orbit is closed.";
            button = "Manage location access";
        }
        TextView note = UiKit.text(this, detail, 12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 10));
        accessCard.addView(note);
        Button manage = secondaryButton(button);
        manage.setOnClickListener(v -> setupLocationAccess());
        accessCard.addView(manage, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 42)));
    }

    private void setupLocationAccess() {
        if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            return;
        }
        if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(this)) {
            if (Build.VERSION.SDK_INT == 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION);
            } else {
                showBackgroundLocationExplanation();
            }
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) {
            RoutineLocationTriggerScheduler.openLocationServices(this);
            return;
        }
        RoutineLocationTriggerScheduler.openAppLocationSettings(this);
    }

    private void showBackgroundLocationExplanation() {
        String label = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Allow background location")
                .setMessage("For arrive/leave Routines to work while Orbit is closed, Android requires Location to be set to “" + label + "”. Open Orbit's app settings, then choose Permissions → Location.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", (d, w) -> RoutineLocationTriggerScheduler.openAppLocationSettings(this))
                .create();
        styleDialog(dialog, false);
        dialog.show();
    }

    private void showTransitionMenu() {
        String[] labels = {"When I arrive", "When I leave"};
        int selected = RoutineTriggerStore.LOCATION_EXIT.equals(transition) ? 1 : 0;
        UiKit.showOrbitMenu(this, transitionButton, labels, selected, (index, label) -> {
            transition = index == 1 ? RoutineTriggerStore.LOCATION_EXIT : RoutineTriggerStore.LOCATION_ENTER;
            dirty = true;
            refreshFields();
        });
    }

    private void showSavedPlaceMenu() {
        List<SavedPlaceStore.Place> places = SavedPlaceStore.list(this);
        if (places.isEmpty()) {
            Toast.makeText(this, "No saved places yet. Add one first.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SavedPlacesActivity.class));
            return;
        }
        String[] labels = new String[places.size() + 1];
        int selected = -1;
        for (int i = 0; i < places.size(); i++) {
            labels[i] = places.get(i).name;
            if (places.get(i).id.equals(selectedSavedPlaceId)) selected = i;
        }
        labels[places.size()] = "Manage saved places…";
        UiKit.showOrbitMenu(this, savedPlaceButton, labels, selected, (index, label) -> {
            if (index >= places.size()) {
                startActivity(new Intent(this, SavedPlacesActivity.class));
                return;
            }
            SavedPlaceStore.Place place = places.get(index);
            selectedSavedPlaceId = place.id;
            nameField.setText(place.name);
            latitudeField.setText(coordinate(place.latitude));
            longitudeField.setText(coordinate(place.longitude));
            dirty = true;
            refreshFields();
        });
    }

    private void showRadiusMenu() {
        String[] labels = {"100 m radius", "200 m radius", "300 m radius", "500 m radius", "1 km radius", "2 km radius", "5 km radius"};
        float[] values = {100f, 200f, 300f, 500f, 1000f, 2000f, 5000f};
        int selected = 1;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - radiusMeters) < 0.5f) { selected = i; break; }
        }
        UiKit.showOrbitMenu(this, radiusButton, labels, selected, (index, label) -> {
            radiusMeters = values[Math.max(0, Math.min(values.length - 1, index))];
            dirty = true;
            refreshFields();
        });
    }

    private void captureCurrentLocation() {
        if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            captureAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) {
            Toast.makeText(this, "Turn on Android location first.", Toast.LENGTH_SHORT).show();
            RoutineLocationTriggerScheduler.openLocationServices(this);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            Toast.makeText(this, "Android location service is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        Location cached = bestLastKnown(lm);
        if (cached != null && System.currentTimeMillis() - cached.getTime() <= 120_000L) {
            applyLocation(cached);
            return;
        }
        String provider = preferredProvider(lm);
        if (provider == null) {
            if (cached != null) applyLocation(cached);
            else Toast.makeText(this, "Could not find an enabled location provider.", Toast.LENGTH_SHORT).show();
            return;
        }
        currentLocationButton.setEnabled(false);
        currentLocationButton.setText("Finding location…");
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                final Location fallback = cached;
                lm.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                    if (location != null) applyLocation(location);
                    else if (fallback != null) applyLocation(fallback);
                    else locationFailed();
                });
            } else {
                final Location fallback = cached;
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (location != null) applyLocation(location);
                        else if (fallback != null) applyLocation(fallback);
                        else locationFailed();
                    }
                    @Override public void onProviderDisabled(String provider) { if (fallback != null) applyLocation(fallback); else locationFailed(); }
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                }, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
            locationFailed();
        } catch (Exception ignored) {
            if (cached != null) applyLocation(cached); else locationFailed();
        }
    }

    private String preferredProvider(LocationManager lm) {
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        } catch (Exception ignored) {}
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        } catch (Exception ignored) {}
        List<String> providers;
        try { providers = lm.getProviders(true); } catch (Exception ignored) { providers = null; }
        return providers == null || providers.isEmpty() ? null : providers.get(0);
    }

    private Location bestLastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (best == null || candidate.getTime() > best.getTime() ||
                        (candidate.getTime() == best.getTime() && candidate.getAccuracy() < best.getAccuracy())) {
                    best = candidate;
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    private void applyLocation(Location location) {
        runOnUiThread(() -> {
            currentLocationButton.setEnabled(true);
            currentLocationButton.setText("Use my current location");
            selectedSavedPlaceId = "";
            latitudeField.setText(coordinate(location.getLatitude()));
            longitudeField.setText(coordinate(location.getLongitude()));
            if (nameField.getText().toString().trim().isEmpty()) nameField.setText("My location");
            refreshFields();
            dirty = true;
            Toast.makeText(this, "Current location added.", Toast.LENGTH_SHORT).show();
        });
    }

    private void locationFailed() {
        runOnUiThread(() -> {
            currentLocationButton.setEnabled(true);
            currentLocationButton.setText("Use my current location");
            Toast.makeText(this, "Orbit could not get a current location fix. Try again outdoors or paste coordinates.", Toast.LENGTH_LONG).show();
        });
    }

    private void save() {
        String name = RoutineTriggerStore.sanitizeLocationName(nameField.getText().toString());
        if (name.isEmpty()) {
            Toast.makeText(this, "Give this location a name.", Toast.LENGTH_SHORT).show();
            return;
        }
        Double latitude = parseDouble(latitudeField.getText().toString());
        Double longitude = parseDouble(longitudeField.getText().toString());
        if (latitude == null || latitude < -90d || latitude > 90d || longitude == null || longitude < -180d || longitude > 180d) {
            Toast.makeText(this, "Use valid latitude and longitude coordinates.", Toast.LENGTH_LONG).show();
            return;
        }
        RoutineTriggerStore.Trigger trigger = original == null
                ? RoutineTriggerStore.createLocation(routineId, name, latitude, longitude, radiusMeters, transition).withEnabled(enabled)
                : original.withLocation(enabled, name, latitude, longitude, radiusMeters, transition);
        if (RoutineTriggerStore.hasEnabledScheduleConflict(this, trigger)) {
            Toast.makeText(this, "An enabled location trigger with this same area and event already exists for this routine.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!RoutineTriggerStore.upsert(this, trigger)) {
            Toast.makeText(this, "Could not save this location trigger.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean scheduled = !enabled || RoutineLocationTriggerScheduler.schedule(this, trigger);
        if (enabled && !scheduled) {
            if (RoutineLocationTriggerScheduler.ready(this)) {
                Toast.makeText(this, "Saved, but Android could not start location monitoring. Check Location access and try enabling the trigger again.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Saved. Finish Location setup to activate this trigger in the background.", Toast.LENGTH_LONG).show();
            }
        }
        dirty = false;
        finish();
    }

    private void confirmDiscard() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Discard location-trigger changes?")
                .setMessage("Your unsaved location and trigger changes will be lost.")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard", (d, w) -> { dirty = false; finish(); })
                .create();
        styleDialog(dialog, true);
        dialog.show();
    }

    private Double parseDouble(String value) {
        try { return Double.parseDouble(value.trim()); }
        catch (Exception ignored) { return null; }
    }

    private String coordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private EditText textField(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(UiKit.TEXT);
        e.setHintTextColor(UiKit.MUTED);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        e.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), UiKit.accent(this), 15, this));
        e.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) dirty = true; });
        return e;
    }

    private EditText numberField(String text) {
        EditText e = textField("0.000000", InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setText(text);
        return e;
    }

    private TextView label(String s) {
        TextView t = UiKit.text(this, s, 11, UiKit.MUTED, true);
        t.setLetterSpacing(.12f);
        t.setPadding(2, UiKit.dp(this, 10), 0, UiKit.dp(this, 6));
        return t;
    }

    private Button selectorButton(String s) {
        Button b = secondaryButton(s);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        return b;
    }

    private LinearLayout.LayoutParams selectorLp() { return new LinearLayout.LayoutParams(-1, UiKit.dp(this, 50)); }
    private LinearLayout.LayoutParams fieldLp() { return new LinearLayout.LayoutParams(-1, UiKit.dp(this, 50)); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        c.setElevation(UiKit.dp(this, 2));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        b.setContentDescription(desc);
        b.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        UiKit.pressScale(b);
        return b;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(UiKit.onAccent(this));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), UiKit.accent(this), 15, this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private void styleDialog(AlertDialog d, boolean destructive) {
        UiKit.styleOrbitDialog(d, this, destructive);
    }

    private void styleShown(AlertDialog d, boolean destructive) {
        UiKit.applyDialogTypography(d);
        tint(d.getWindow() == null ? null : d.getWindow().getDecorView());
        Button p = d.getButton(AlertDialog.BUTTON_POSITIVE), n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (p != null) p.setTextColor(destructive ? Color.rgb(239,105,105) : UiKit.accent(this));
        if (n != null) n.setTextColor(UiKit.accent(this));
    }

    private void tint(View v) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof Button)) ((TextView) v).setTextColor(UiKit.TEXT);
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) tint(((ViewGroup) v).getChildAt(i));
        }
    }
}
