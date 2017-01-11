/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v13.view;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.support.annotation.RequiresApi;
import android.view.View;

@RequiresApi(24)
@TargetApi(24)
class ViewCompatApi24 {
    public static boolean startDragAndDrop(View v, ClipData data,
           View.DragShadowBuilder shadowBuilder, Object localState, int flags) {
        return v.startDragAndDrop(data, shadowBuilder, localState, flags);
    }

    public static void cancelDragAndDrop(View v) {
        v.cancelDragAndDrop();
    }

    public static void updateDragShadow(View v, View.DragShadowBuilder shadowBuilder) {
        v.updateDragShadow(shadowBuilder);
    }

    private ViewCompatApi24() {}
}
