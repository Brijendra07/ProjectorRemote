package com.brijendra.projectorhelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Lists the projector's launchable apps with real names, icons and TV banners. */
object AppsProvider {

    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, JSONObject>()
        val cats = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)
        for ((cat, tv) in cats) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val found = try { pm.queryIntentActivities(intent, 0) } catch (_: Throwable) { emptyList() }
            for (ri in found) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                // Just check the resource ids — loading every banner made the list slow.
                val hasBanner = ai.banner != 0 || (ai.applicationInfo?.banner ?: 0) != 0
                seen[pkg] = JSONObject()
                    .put("pkg", pkg)
                    .put("label", ri.loadLabel(pm).toString())
                    .put("component", "$pkg/${ai.name}")
                    .put("tv", tv)
                    .put("banner", hasBanner)
            }
        }
        return JSONArray(seen.values.sortedBy { it.optString("label").lowercase() })
    }

    fun iconPng(ctx: Context, pkg: String, size: Int = 192): ByteArray? {
        val d = try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { return null }
        return render(d, size, size)
    }

    fun bannerPng(ctx: Context, component: String): ByteArray? {
        val pm = ctx.packageManager
        val cn = ComponentName.unflattenFromString(component) ?: return null
        val d = try {
            pm.getActivityBanner(cn) ?: pm.getApplicationBanner(cn.packageName)
        } catch (_: Throwable) { null } ?: return null
        return render(d, 320, 180)
    }

    private fun render(d: Drawable, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
