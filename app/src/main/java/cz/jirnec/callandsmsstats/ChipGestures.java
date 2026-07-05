package cz.jirnec.callandsmsstats;

import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Vodorovné máchnutí (fling) přepne na předchozí/další chip v dané ChipGroup.
 * Máchnutí doleva = další chip, doprava = předchozí.
 *
 * Detektor se napojuje na úrovni celé obrazovky (Activity.dispatchTouchEvent),
 * aby gesto fungovalo i nad prázdným seznamem.
 */
public final class ChipGestures {

    private ChipGestures() {
    }

    public static GestureDetector detector(View feedbackView, ChipGroup group) {
        return new GestureDetector(feedbackView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > Math.abs(dy)
                                && Math.abs(dx) > 80
                                && Math.abs(velocityX) > 300) {
                            move(feedbackView, group, dx < 0 ? 1 : -1);
                            return true;
                        }
                        return false;
                    }
                });
    }

    private static void move(View feedbackView, ChipGroup group, int direction) {
        List<Chip> chips = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip) {
                chips.add((Chip) child);
            }
        }
        if (chips.isEmpty()) {
            return;
        }

        int checkedId = group.getCheckedChipId();
        int index = 0;
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).getId() == checkedId) {
                index = i;
                break;
            }
        }

        int target = index + direction;
        if (target < 0 || target >= chips.size()) {
            return; // na krajích necyklíme
        }

        Chip chip = chips.get(target);
        chip.setChecked(true); // spustí posluchač ChipGroup → přenačtení
        feedbackView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

        View parent = (View) group.getParent();
        if (parent instanceof HorizontalScrollView) {
            parent.post(() -> ((HorizontalScrollView) parent)
                    .smoothScrollTo(Math.max(0, chip.getLeft() - 32), 0));
        }
    }
}
