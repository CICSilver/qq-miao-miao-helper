package com.xianyunb.qqmiaohelper;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/**
 * 下拉通知栏里的快捷开关。
 *
 * 加这个是因为「暂停」原本没有顺手的入口 —— 只有分项开关，还得先打开 App。
 * 现在从任何界面下拉一下就能停，不用退出聊天。
 *
 * 它切换的是 {@link CatConfig#setMasterEnabled}，与主界面顶部的总开关是同一个值。
 *
 * 注意：这只是**暂停**。无障碍权限仍然授予着，要彻底停止请去系统设置里关闭
 * 无障碍服务，或者卸载本应用 —— 卸载 QQ 是没用的，服务属于本应用而不是 QQ。
 *
 * Android 7.0（API 24）以上才有快捷设置磁贴；更低版本上系统不会加载此服务。
 */
@RequiresApi(Build.VERSION_CODES.N)
public class MeowTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        CatConfig config = new CatConfig(this);
        config.setMasterEnabled(!config.isMasterEnabled());
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = new CatConfig(this).isMasterEnabled();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(on ? "喵化中" : "喵化已暂停");
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_meow));
        tile.updateTile();
    }
}
