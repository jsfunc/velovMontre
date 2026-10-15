package fr.velovmontre.phone;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xhdpi", application = PhoneActivityTest.OfflineApplication.class)
public class PhoneActivityTest {
    public static class OfflineApplication extends PhoneApplication {
        @Override public void onCreate() { super.onCreate(); repository().pauseForBenchmark(); }
        @Override public void loadCatalog() { }
        @Override public void publish() { }
        @Override public void checkReceipt() { }
    }
    @Test public void displaysFullNamesAndRestoresGroupAndEditingState() {
        var controller = Robolectric.buildActivity(PhoneActivity.class).setup();
        var activity = controller.get();
        find(activity.getWindow().getDecorView(), "Liste").performClick();
        assertNotNull(find(activity.getWindow().getDecorView(), "Cité scolaire internationale"));
        find(activity.getWindow().getDecorView(), "Groupe 2").performClick();
        assertNotNull(find(activity.getWindow().getDecorView(), "Charcot / Résidence universitaire"));
        find(activity.getWindow().getDecorView(), "Modifier").performClick();
        assertNotNull(find(activity.getWindow().getDecorView(), "Terminer"));
        Bundle state = new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        var recreated = Robolectric.buildActivity(PhoneActivity.class).create(state).start().resume().visible();
        assertNotNull(find(recreated.get().getWindow().getDecorView(), "Terminer"));
        assertNotNull(find(recreated.get().getWindow().getDecorView(), "Charcot / Résidence universitaire"));
        recreated.pause().stop().destroy();
    }
    @Test public void numberSearchCanRestoreRemovedStationAndPersistsIt() {
        var app = (PhoneApplication) RuntimeEnvironment.getApplication();
        app.save(app.selection().current().remove("7046"));
        var controller = Robolectric.buildActivity(PhoneActivity.class).setup();
        var activity = controller.get();
        find(activity.getWindow().getDecorView(), "Modifier").performClick();
        find(activity.getWindow().getDecorView(), "Ajouter une station").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = input(dialog.getWindow().getDecorView());
        input.setText("7046");
        View result = find(dialog.getWindow().getDecorView(), "7046 · Place de l’École");
        assertNotNull(result); result.performClick();
        assertFalse(dialog.isShowing());
        assertEquals("7046", app.selection().current().groups().get(0).get(7).id());
        assertEquals(app.selection().current(), new fr.velovmontre.selection.SelectionStore(app).current());
        controller.pause().stop().destroy();
    }
    @Test public void mapIsDefaultAndEditingReturnsToTheChosenView() {
        var controller = Robolectric.buildActivity(PhoneActivity.class).setup();
        var activity = controller.get();
        View decor = activity.getWindow().getDecorView();
        assertTrue(find(decor, "Carte").isSelected());
        assertNotNull(find(decor, "Recentrer"));
        find(decor, "Modifier").performClick();
        assertNotNull(find(activity.getWindow().getDecorView(), "Place de l’École"));
        find(activity.getWindow().getDecorView(), "Terminer").performClick();
        assertTrue(find(activity.getWindow().getDecorView(), "Carte").isSelected());
        find(activity.getWindow().getDecorView(), "Liste").performClick();
        controller.pause().stop().destroy();
        var recreated = Robolectric.buildActivity(PhoneActivity.class).setup();
        assertTrue(find(recreated.get().getWindow().getDecorView(), "Liste").isSelected());
        assertNotNull(find(recreated.get().getWindow().getDecorView(), "Place de l’École"));
        recreated.pause().stop().destroy();
    }
    @Test public void editMenuReordersAndRemovalRequiresConfirmation() {
        var controller = Robolectric.buildActivity(PhoneActivity.class).setup();
        var app = (PhoneApplication) RuntimeEnvironment.getApplication();
        var activity = controller.get();
        find(activity.getWindow().getDecorView(), "Modifier").performClick();
        View name = find(activity.getWindow().getDecorView(), "Place de l’École");
        ((View) name.getParent().getParent()).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 1, 1);
        assertEquals("7046", app.selection().current().groups().get(0).get(1).id());
        name = find(activity.getWindow().getDecorView(), "Place de l’École");
        ((View) name.getParent().getParent()).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 3, 3);
        assertTrue(app.selection().current().ids().contains("7046"));
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(app.selection().current().ids().contains("7046"));
        controller.pause().stop().destroy();
    }
    @Test public void rendersPhonePreviewAtTypicalPhoneWidth() throws Exception {
        var controller = Robolectric.buildActivity(PhoneActivity.class).setup();
        find(controller.get().getWindow().getDecorView(), "Liste").performClick();
        View content = controller.get().findViewById(android.R.id.content);
        content.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 822, 1782);
        var bitmap = android.graphics.Bitmap.createBitmap(822, 1782, android.graphics.Bitmap.Config.ARGB_8888);
        content.draw(new android.graphics.Canvas(bitmap));
        var directory = new java.io.File("build/reports"); directory.mkdirs();
        try (var output = new java.io.FileOutputStream(new java.io.File(directory, "phone-preview.png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        assertTrue(find(content, "Cité scolaire internationale").getWidth() > 0);
        controller.pause().stop().destroy();
    }
    private static View find(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) for (int i=0; i<group.getChildCount(); i++) {
            View match = find(group.getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
    private static EditText input(View view) {
        if (view instanceof EditText input) return input;
        if (view instanceof ViewGroup group) for (int i=0; i<group.getChildCount(); i++) {
            EditText match = input(group.getChildAt(i)); if (match != null) return match;
        }
        return null;
    }
}
