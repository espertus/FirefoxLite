/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.rocket.tabs

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import org.mozilla.rocket.tabs.TabView.FindListener
import org.mozilla.rocket.tabs.utils.TabUtil
import org.mozilla.rocket.tabs.web.DownloadCallback
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.LinkedList

internal val MSG_FOCUS_TAB = 0x1001
internal val MSG_ADDED_TAB = 0x1002

/**
 * Class to help on tabs management, such as adding or removing tabs.
 */
class TabsSession(private val tabViewProvider: TabViewProvider) {

    private val tabs = LinkedList<Tab>()

    private val notifier: Notifier

    private var focusRef = WeakReference<Tab>(null)

    private val tabsViewListeners = ArrayList<TabsViewListener>()
    private val tabsChromeListeners = ArrayList<TabsChromeListener>()
    private var downloadCallback: DownloadCallback? = null
    private var findListener: FindListener? = null

    /**
     * To get count of tabs in this session.
     *
     * @return count in integer
     */
    val tabsCount: Int
        get() = tabs.size

    /**
     * To get data of tabs to store in persistent storage.
     *
     * @return created TabModel of tabs in this session.
     */
    val tabModelListForPersistence: List<TabModel>
        get() {
            val models = ArrayList<TabModel>()
            for (tab in tabs) {
                models.add(tab.saveModel)
            }
            return models
        }

    /**
     * To get current focused tab.
     *
     * @return current focused tab. Return null if there is not any tab.
     */
    val focusTab: Tab?
        get() = focusRef.get()

    init {
        this.notifier = Notifier(tabViewProvider, this.tabsChromeListeners)
    }

    /**
     * Copy reference of tabs which are held by this session.
     *
     * @return new List which is safe to change its order without effect this session
     */
    fun getTabs(): List<Tab> {
        // create a new list, in case of caller modify this list
        return ArrayList(tabs)
    }

    /**
     * To append tabs from a list of TabModel. If the specified focusTabId exists, the tab associate
     * to the id will be focused, otherwise no tab will be focused.
     *
     *
     * This is asynchronous call.
     * TODO: make it asynchronous
     *
     * @param models
     */
    fun restoreTabs(models: List<TabModel>, focusTabId: String?) {
        var insertPos = 0
        for (model in models) {
            if (!model.isValid()) {
                continue
            }

            val tab = Tab(model)
            bindCallback(tab)
            tabs.add(insertPos++, tab)
        }

        if (tabs.size > 0 && tabs.size == models.size) {
            focusRef = WeakReference<Tab>(getTab(focusTabId))
        }

        for (l in tabsChromeListeners) {
            l.onTabCountChanged(tabs.size)
        }
    }

    /**
     * Add a tab, its attributes are controlled by arguments. The default attributes of a new tab
     * is defined by @see{org.mozilla.focus.tabs.utils.TabUtil}. Usually it 1) has no parent
     * 2) is not opened from external app 3) will not change focus
     *
     * @param url       initial url for this tab
     * @param arguments to contain dispensable arguments to indicate whether the new tab is from-external? should be focused?...etc.
     * @return id for created tab
     */
    fun addTab(url: String, arguments: Bundle): String? {

        return if (TextUtils.isEmpty(url)) {
            null
        } else addTabInternal(url,
                TabUtil.getParentId(arguments),
                TabUtil.isFromExternal(arguments),
                TabUtil.toFocus(arguments),
                arguments)

    }

    /**
     * To drop a tab from list, it will not invoke callback onFocusChanged, and only change focus to nearest tab.
     *
     * @param id the id of tab to be dropped
     */
    fun dropTab(id: String) {
        this.removeTabInternal(id, true)
    }

    /**
     * To close a tab by remove it from list and update tab focus.
     *
     * @param id the id of tab to be closed.
     */
    fun closeTab(id: String) {
        this.removeTabInternal(id, false)
    }

    private fun removeTabInternal(id: String, isDrop: Boolean) {
        val tab = getTab(id) ?: return

        val oldIndex = getTabIndex(id)
        tabs.remove(tab)
        tab.destroy()

        // Update child's parent id to its ancestor
        // TODO: in our current design, the parent of a tab are always locate at left(index -1).
        // hence no need to loop whole list.
        for (t in tabs) {
            if (TextUtils.equals(t.parentId, tab.id)) {
                t.parentId = tab.parentId
            }
        }

        // if the removing tab was focused, we need to update focus
        if (tab === focusRef.get()) {
            if (isDrop) {
                val nextIdx = Math.min(oldIndex, tabs.size - 1)
                focusRef = if (nextIdx == -1)
                    WeakReference<Tab>(null)
                else
                    WeakReference(tabs[nextIdx])
            } else {
                updateFocusOnClosing(tab)
            }
        }

        for (l in tabsChromeListeners) {
            l.onTabCountChanged(tabs.size)
        }
    }

    private fun updateFocusOnClosing(removedTab: Tab) {
        if (TextUtils.isEmpty(removedTab.parentId)) {
            focusRef.clear()
            notifier.notifyTabFocused(null, TabsChromeListener.FACTOR_NO_FOCUS)
        } else if (TextUtils.equals(removedTab.parentId, Tab.ID_EXTERNAL)) {
            focusRef.clear()
            notifier.notifyTabFocused(null, TabsChromeListener.FACTOR_BACK_EXTERNAL)
        } else {
            focusRef = WeakReference<Tab>(getTab(removedTab.parentId!!))
            notifier.notifyTabFocused(focusRef.get(), TabsChromeListener.FACTOR_TAB_REMOVED)
        }
    }

    /**
     * To focus a tab from list.
     *
     * @param id the id of tab to be focused.
     */
    fun switchToTab(id: String) {
        val nextTab = getTab(id)
        if (nextTab != null) {
            focusRef = WeakReference(nextTab)
        }

        notifier.notifyTabFocused(nextTab, TabsChromeListener.FACTOR_TAB_SWITCHED)
    }

    /**
     * To check whether this session has any tabs
     *
     * @return true if this session has at least one tab
     */
    fun hasTabs(): Boolean {
        return tabs.size > 0
    }

    /**
     * To add @see{TabsViewListener} to this session.
     *
     * @param listener
     */
    fun addTabsViewListener(listener: TabsViewListener) {
        if (!this.tabsViewListeners.contains(listener)) {
            this.tabsViewListeners.add(listener)
        }
    }

    /**
     * To add @see{TabsChromeListener} to this session.
     *
     * @param listener
     */
    fun addTabsChromeListener(listener: TabsChromeListener) {
        if (!this.tabsChromeListeners.contains(listener)) {
            this.tabsChromeListeners.add(listener)
        }
    }

    /**
     * To remove @see{TabsViewListener} from this session.
     *
     * @param listener
     */
    fun removeTabsViewListener(listener: TabsViewListener) {
        this.tabsViewListeners.remove(listener)
    }

    /**
     * To remove @see{TabsChromeListener} from this session.
     *
     * @param listener
     */
    fun removeTabsChromeListener(listener: TabsChromeListener) {
        this.tabsChromeListeners.remove(listener)
    }

    /**
     * To specify @see{DownloadCallback} to this session, this method will replace existing one. It
     * also replace DownloadCallback from any existing Tab.
     *
     * @param downloadCallback
     */
    fun setDownloadCallback(downloadCallback: DownloadCallback?) {
        this.downloadCallback = downloadCallback
        if (hasTabs()) {
            for (tab in tabs) {
                tab.setDownloadCallback(downloadCallback)
            }
        }
    }

    fun setFindListener(findListener: FindListener?) {
        this.findListener = findListener
        if (hasTabs()) {
            for (tab in tabs) {
                tab.setFindListener(findListener)
            }
        }
    }

    /**
     * To destroy this session, and it also destroy any tabs in this session.
     * This method should be called after any View has been removed from view system.
     * No other methods may be called on this session after destroy.
     */
    fun destroy() {
        for (tab in tabs) {
            tab.destroy()
        }
    }

    /**
     * To pause this session, and it also pause any tabs in this session.
     */
    fun pause() {
        for (tab in tabs) {
            tab.pause()
        }
    }

    /**
     * To resume this session after a previous call to @see{#pause}
     */
    fun resume() {
        for (tab in tabs) {
            tab.resume()
        }
    }

    private fun bindCallback(tab: Tab) {
        tab.setTabViewClient(TabViewClientImpl(tab))
        tab.setTabChromeClient(TabChromeClientImpl(tab))
        tab.setDownloadCallback(downloadCallback)
        tab.setFindListener(findListener)
    }

    private fun addTabInternal(url: String?,
                               parentId: String?,
                               fromExternal: Boolean,
                               toFocus: Boolean,
                               arguments: Bundle?): String {

        val tab = Tab()
        tab.setUrl(url!!)

        bindCallback(tab)

        val parentIndex = if (TextUtils.isEmpty(parentId)) -1 else getTabIndex(parentId!!)
        if (fromExternal) {
            tab.parentId = Tab.ID_EXTERNAL
            tabs.add(tab)
        } else {
            insertTab(parentIndex, tab)
        }

        notifier.notifyTabAdded(tab, arguments)

        focusRef = if (toFocus || fromExternal) WeakReference(tab) else focusRef

        tab.initializeView(this.tabViewProvider)

        if (toFocus || fromExternal) {
            notifier.notifyTabFocused(tab, TabsChromeListener.FACTOR_TAB_ADDED)
        }

        for (l in tabsChromeListeners) {
            l.onTabCountChanged(tabs.size)
        }
        return tab.id
    }

    private fun getTab(id: String?): Tab? {
        val index = getTabIndex(id)
        return if (index == -1) null else tabs[index]
    }

    private fun getTabIndex(id: String?): Int {
        if (id == null) {
            return -1
        }

        for (i in tabs.indices) {
            val tab = tabs[i]
            if (tab.id == id) {
                return i
            }
        }
        return -1
    }

    private fun insertTab(parentIdx: Int, tab: Tab) {
        val parentTab = if (parentIdx >= 0 && parentIdx < tabs.size)
            tabs[parentIdx]
        else
            null
        if (parentTab == null) {
            tabs.add(tab)
            return
        } else {
            tabs.add(parentIdx + 1, tab)
        }

        // TODO: in our current design, the parent of a tab are always locate at left(index -1).
        //       hence no need to loop whole list.
        // if the parent-tab has a child, give it a new parent
        for (t in tabs) {
            if (parentTab.id == t.parentId) {
                t.parentId = tab.id
            }
        }

        // update family relationship
        tab.parentId = parentTab.id
    }

    internal inner class TabViewClientImpl(var source: Tab) : TabViewClient() {

        private fun setTitle() {
            if (source.tabView == null) {
                return
            }
            source.title = source.tabView!!.title
        }

        override fun onPageStarted(url: String) {
            source.url = url
            setTitle()

            // FIXME: workaround for 'dialog new window'
            if (source.url != null) {
                for (l in tabsViewListeners) {
                    l.onTabStarted(source)
                }
            }
        }

        override fun onPageFinished(isSecure: Boolean) {
            setTitle()

            for (l in tabsViewListeners) {
                l.onTabFinished(source, isSecure)
            }
        }

        override fun onURLChanged(url: String) {
            source.url = url
            setTitle()

            for (l in tabsViewListeners) {
                l.onURLChanged(source, url)
            }
        }

        override fun handleExternalUrl(url: String): Boolean {
            // only return false if none of listeners handled external url.
            for (l in tabsViewListeners) {
                if (l.handleExternalUrl(url)) {
                    return true
                }
            }
            return false
        }

        override fun updateFailingUrl(url: String, updateFromError: Boolean) {
            for (l in tabsViewListeners) {
                l.updateFailingUrl(source, url, updateFromError)
            }
        }
    }


    private inner class TabChromeClientImpl internal constructor(
            internal var source: Tab
    ) : TabChromeClient() {

        override fun onCreateWindow(isDialog: Boolean, isUserGesture: Boolean, msg: Message?): Boolean {
            if (msg == null) {
                return false
            }

            val id = addTabInternal(null,
                    source.id,
                    false,
                    isUserGesture, null)

            val tab = getTab(id)
                    ?: // FIXME: why null?
                    return false
            if (tab.tabView == null) {
                throw RuntimeException("webview is null, previous creation failed")
            }
            tab.tabView!!.bindOnNewWindowCreation(msg)
            return true
        }

        override fun onCloseWindow(tabView: TabView) {
            if (source.tabView === tabView) {
                for (i in tabs.indices) {
                    val tab = tabs[i]
                    if (tab.tabView === tabView) {
                        closeTab(tab.id)
                    }
                }
            }
        }

        override fun onProgressChanged(progress: Int) {
            for (l in tabsChromeListeners) {
                l.onProgressChanged(source, progress)
            }
        }

        override fun onShowFileChooser(tabView: TabView, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
            for (l in tabsChromeListeners) {
                if (l.onShowFileChooser(source, tabView, filePathCallback, fileChooserParams)) {
                    return true
                }
            }
            return false
        }

        override fun onReceivedTitle(view: TabView, title: String) {
            for (l in tabsChromeListeners) {
                l.onReceivedTitle(source, title)
            }
        }

        override fun onReceivedIcon(view: TabView, icon: Bitmap) {
            source.favicon = icon
            for (l in tabsChromeListeners) {
                l.onReceivedIcon(source, icon)
            }
        }

        override fun onLongPress(hitTarget: TabView.HitTarget) {
            for (l in tabsChromeListeners) {
                l.onLongPress(source, hitTarget)
            }
        }

        override fun onEnterFullScreen(callback: TabView.FullscreenCallback, view: View?) {
            for (l in tabsChromeListeners) {
                l.onEnterFullScreen(source, callback, view)
            }
        }

        override fun onExitFullScreen() {
            for (l in tabsChromeListeners) {
                l.onExitFullScreen(source)
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            for (l in tabsChromeListeners) {
                l.onGeolocationPermissionsShowPrompt(source, origin, callback)
            }
        }
    }

    /**
     * A class to attach to UI thread for sending message.
     */
    private class Notifier internal constructor(
            private val tabViewProvider: TabViewProvider,
            private val chromeListeners: List<TabsChromeListener>
    ) : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_FOCUS_TAB -> focusTab(msg.obj as Tab?, msg.arg1)
                MSG_ADDED_TAB -> addedTab(msg)
                else -> {
                }
            }
        }

        fun notifyTabAdded(tab: Tab, arguments: Bundle?) {
            val msg = this.obtainMessage(MSG_ADDED_TAB)
            val pojo = NotifierPojo()
            pojo.tab = tab
            pojo.arguements = arguments
            msg.obj = pojo
            this.sendMessage(msg)
        }

        fun addedTab(msg: Message) {
            val pojo = msg.obj as NotifierPojo

            for (l in this.chromeListeners!!) {
                l.onTabAdded(pojo.tab!!, pojo.arguements)
            }
        }

        fun notifyTabFocused(tab: Tab?, @TabsChromeListener.Factor factor: Int) {
            val msg = this.obtainMessage(MSG_FOCUS_TAB)
            msg.obj = tab
            msg.arg1 = factor
            this.sendMessage(msg)
        }

        private fun focusTab(tab: Tab?, @TabsChromeListener.Factor factor: Int) {

            if (tab != null && tab.tabView == null) {
                tab.initializeView(this.tabViewProvider)
            }

            for (l in this.chromeListeners!!) {
                l.onFocusChanged(tab, factor)
            }
        }

        private class NotifierPojo {
            var tab: Tab? = null
            var arguements: Bundle? = null
        }
    }
}
