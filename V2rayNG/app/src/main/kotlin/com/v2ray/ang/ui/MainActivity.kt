package com.v2ray.ang.ui

//import com.v2ray.ang.InappBuyActivity
import SpeedUpVPN.VpnEncrypt
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.support.design.widget.NavigationView
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.widget.helper.ItemTouchHelper
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.tbruyelle.rxpermissions.RxPermissions
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.AngConfigManager.configs
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.V2rayConfigUtil
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.lang.ref.SoftReference
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 0
        private const val REQUEST_SCAN = 1
        private const val REQUEST_FILE_CHOOSER = 2
        private const val REQUEST_SCAN_URL = 3
        lateinit var instance: MainActivity
            private set
    }

    var isRunning = false
        set(value) {
            field = value
            adapter.changeable = !value
            if (value) {
                fab.imageResource = R.drawable.ic_v
                tv_test_state.text = getString(R.string.connection_connected)
            } else {
                fab.imageResource = R.drawable.ic_v_idle
                tv_test_state.text = getString(R.string.connection_not_connected)
            }
            hideCircle()
        }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private var mItemTouchHelper: ItemTouchHelper? = null
    lateinit var mAdView : AdView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)
        MobileAds.initialize(this)
        mAdView = findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        mAdView.loadAd(adRequest)
        title = getString(R.string.title_server)
        setSupportActionBar(toolbar)

        fab.setOnClickListener {
            if (isRunning) {
                Utils.stopVService(this)
            } else {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    startActivityForResult(intent, REQUEST_CODE_VPN_PREPARE)
                }
            }
        }
        layout_test.setOnClickListener {
            if (isRunning) {
                val socksPort = 10808//Utils.parseInt(defaultDPreference.getPrefString(SettingsActivity.PREF_SOCKS_PORT, "10808"))

                tv_test_state.text = getString(R.string.connection_test_testing)
                doAsync {
                    val result = Utils.testConnection(this@MainActivity, socksPort)
                    uiThread {
                        tv_test_state.text = Utils.getEditable(result)
                    }
                }
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        var recsite1= resources.getString(R.string.recommended_site_1)
        recommended_site_1.setBackgroundColor(Color.TRANSPARENT);
        recommended_site_1.loadData(recsite1,"text/html; charset=utf-8",  "UTF-8")

        recycler_view.setHasFixedSize(true)
        val llm=RecyclerViewNoBugLinearLayoutManager(this)
        llm.stackFromEnd = true
        llm.reverseLayout = true
        recycler_view.layoutManager =llm
        recycler_view.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(recycler_view)


        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()
        nav_view.setNavigationItemSelectedListener(this)
        if(v2RayApplication.defaultDPreference.getPrefBoolean(SettingsActivity.PREF_isAutoUpdateServers, true)){
            if(v2RayApplication.defaultDPreference.getPrefBoolean(SettingsActivity.PREF_GET_FREE_SERVERS, false)){
                importFreeSubs()
            }
            importConfigViaBuildInSub()
        }
    }

    fun startV2Ray() {
        if (AngConfigManager.configs.index < 0) {
            return
        }
        showCircle()
//        toast(R.string.toast_services_start)
        if (!Utils.startVService(this)) {
            hideCircle()
        }
    }

    override fun onStart() {
        super.onStart()
        isRunning = false

//        val intent = Intent(this.applicationContext, V2RayVpnService::class.java)
//        intent.`package` = AppConfig.ANG_PACKAGE
//        bindService(intent, mConnection, BIND_AUTO_CREATE)

        mMsgReceive = ReceiveMessageHandler(this@MainActivity)
        registerReceiver(mMsgReceive, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onStop() {
        super.onStop()
        if (mMsgReceive != null) {
            unregisterReceiver(mMsgReceive)
            mMsgReceive = null
        }
    }

    public override fun onResume() {
        super.onResume()
        adapter.updateConfigList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_VPN_PREPARE ->
                if (resultCode == RESULT_OK) {
                    startV2Ray()
                }
            REQUEST_SCAN ->
                if (resultCode == RESULT_OK) {
                    importBatchConfig(data?.getStringExtra("SCAN_RESULT"))
                }
            REQUEST_FILE_CHOOSER -> {
                if (resultCode == RESULT_OK) {
                    val uri = data!!.data
                    if (uri != null) {
                        readContentFromUri(uri)
                    }
                }
            }
            REQUEST_SCAN_URL ->
                if (resultCode == RESULT_OK) {
                    importConfigCustomUrl(data?.getStringExtra("SCAN_RESULT"))
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(REQUEST_SCAN)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            startActivity<ServerActivity>("position" to -1, "isRunning" to isRunning)
            adapter.updateConfigList()
            true
        }
        R.id.import_manually_ss -> {
            startActivity<Server3Activity>("position" to -1, "isRunning" to isRunning)
            adapter.updateConfigList()
            true
        }
        R.id.import_manually_socks -> {
            startActivity<Server4Activity>("position" to -1, "isRunning" to isRunning)
            adapter.updateConfigList()
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(REQUEST_SCAN_URL)
            true
        }

//        R.id.sub_setting -> {
//            startActivity<SubSettingActivity>()
//            true
//        }
        R.id.servers_update -> {
            if(v2RayApplication.defaultDPreference.getPrefBoolean(SettingsActivity.PREF_GET_FREE_SERVERS, false)){
                importFreeSubs()
            }
            importConfigViaBuildInSub()
            true
        }
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.export_all -> {
            if (AngConfigManager.shareAll2Clipboard() == 0) {
                //remove toast, otherwise it will block previous warning message
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            for (k in 0 until configs.vmess.count()) {
                configs.vmess[k].testResult = ""
                adapter.updateConfigList()
            }
            for (k in 0 until configs.vmess.count()) {
                if (configs.vmess[k].configType != AppConfig.EConfigType.Custom) {
                    doAsync {
                        configs.vmess[k].testResult = Utils.tcping(configs.vmess[k].address, configs.vmess[k].port)
                        uiThread {
                            adapter.updateSelectedItem(k)
                        }
                    }
                }
            }
            true
        }

        R.id.real_ping_all -> {
            if (isRunning) {
                Utils.stopVService(this)
            }
            for (k in 0 until configs.vmess.count()) {
                configs.vmess[k].testResult = ""
                adapter.updateConfigList()
            }
            doAsync{
                for (k in configs.vmess.count()-1 downTo 0) {
                    try {
                        if (configs.vmess[k].configType != AppConfig.EConfigType.Custom) {
                            configs.vmess[k].testResult = VpnEncrypt.testing
                            uiThread {
                                var llm = recycler_view.layoutManager as RecyclerViewNoBugLinearLayoutManager
                                llm.scrollToPositionWithOffset(k, 0)
                                llm.stackFromEnd = true
                                adapter.updateSelectedItem(k)
                            }
                            Utils.testVService(this@MainActivity,k)
                            while(configs.vmess[k].testResult==VpnEncrypt.testing)Thread.sleep(300)
                        }
                    }
                    catch (e:Exception){
                        e.printStackTrace()
                    }
                }
                configs.vmess.sortBy {it.testResult}
                AngConfigManager.storeConfigFile()
                uiThread {toast(R.string.toast_test_ended)}
            }
            true
        }

        R.id.retest_invalid_servers -> {
            if (isRunning) {
                Utils.stopVService(this)
            }
            doAsync{
                for (k in configs.vmess.count()-1 downTo 0) {
                    try {
                        if (configs.vmess[k].configType != AppConfig.EConfigType.Custom) {

                        if (configs.vmess[k].testResult != "-1ms"
                                && configs.vmess[k].testResult != ""
                                && configs.vmess[k].testResult != VpnEncrypt.testing)continue

                            configs.vmess[k].testResult = VpnEncrypt.testing
                            uiThread {
                                var llm = recycler_view.layoutManager as RecyclerViewNoBugLinearLayoutManager
                                llm.scrollToPositionWithOffset(k, 0)
                                llm.stackFromEnd = true
                                adapter.updateSelectedItem(k)
                            }
                            Utils.testVService(this@MainActivity,k)
                            while(configs.vmess[k].testResult==VpnEncrypt.testing)Thread.sleep(300)
                        }
                    }
                    catch (e:Exception){
                        e.printStackTrace()
                    }
                }
                configs.vmess.sortBy {it.testResult}
                AngConfigManager.storeConfigFile()
                uiThread {toast(R.string.toast_test_ended)}
            }
            true
        }

        R.id.remove_invalid_servers -> {
            try {
                for (k in configs.vmess.count() - 1 downTo 0) {
                    if (configs.vmess[k].testResult == VpnEncrypt.testing || configs.vmess[k].testResult == "")configs.vmess[k].testResult = "0"
                    if (configs.vmess[k].testResult == "-1ms") AngConfigManager.removeServer(k)
                }
                configs.vmess.trimToSize()
                configs.vmess.sortBy { it.testResult.replace("ms", "").toInt() }
                //configs.vmess.reverse()
                AngConfigManager.storeConfigFile()
                adapter.updateConfigList()
            }
            catch (e:Exception){
                e.printStackTrace()
            }
            true
        }
//        R.id.logcat -> {
//            startActivity<LogcatActivity>()
//            true
//        }
        else -> super.onOptionsItemSelected(item)
    }


    /**
     * import config from qrcode
     */
    private fun importQRcode(requestCode: Int): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this)
                .request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it)
                        startActivityForResult<ScannerActivity>(requestCode)
                    else
                        toast(R.string.toast_permission_denied)
                }
//        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?, subid: String = "") : Boolean {
        val count = AngConfigManager.importBatchConfig(server, subid)
        if (count > 0) {
            toast(R.string.toast_success)
            adapter.updateConfigList()
	    return true
        } else {
            toast(R.string.toast_failure)
	    return false
        }
    }

    private fun importConfigCustomClipboard()
            : Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun importConfigCustomUrlClipboard()
            : Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    private fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            doAsync {
                val configText = URL(url).readText()
                uiThread {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }
    /**
     * import config from BuildinSub
     */
    private fun importConfigViaBuildInSub(){
        toast(R.string.update_builtin_servers)
        var  builtinSubUrls  = resources.getStringArray(R.array.builtinSubUrls)
        doAsync {
            VpnEncrypt.builtinServersUpdated=false
            for (i in builtinSubUrls.indices) {
                try {
                    val url = builtinSubUrls[i]
                    //Log.d("Main", url)
                    val configText = URL(url).readText()
                    //if (configText.isNotEmpty()&&configText.isNotBlank())VpnEncrypt.builtinServersUpdated=true
                    uiThread {
                        // builtinSub set id 999
                        VpnEncrypt.builtinServersUpdated = importBatchConfig(VpnEncrypt.aesDecrypt(configText), VpnEncrypt.builtinSubID)
                    }
                    Thread.sleep(10_000)
                    Log.d("VpnEncrypt", VpnEncrypt.builtinServersUpdated.toString())
                    if(VpnEncrypt.builtinServersUpdated){
                        Log.d("VpnEncrypt", "VpnEncrypt builtinServersUpdated,break")
                        break
                    }
                }
                catch (e: Exception) {
                    Log.e("VpnEncrypt",e.stackTrace.first().toString())
                }
            }
            if(!VpnEncrypt.builtinServersUpdated)uiThread{toast(R.string.connection_test_fail)}
        }
    }

    /**
     * import free sub
     */
    fun importFreeSubs(): Boolean {
        try {
            toast(R.string.title_sub_update)
//            doAsync {
//                val configText = URL(VpnEncrypt.freeSubUrl).readText()
//                uiThread {
//                    importBatchConfig(Utils.decode(configText), VpnEncrypt.freeSubID)
//                }
//            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    private fun importConfigViaSub()
            : Boolean {
        try {
            toast(R.string.title_sub_update)
            val subItem = AngConfigManager.configs.subItem
            for (k in 0 until subItem.count()) {
                if (TextUtils.isEmpty(subItem[k].id)
                        || TextUtils.isEmpty(subItem[k].remarks)
                        || TextUtils.isEmpty(subItem[k].url)
                ) {
                    continue
                }
                val id = subItem[k].id
                val url = subItem[k].url
                if (!Utils.isValidUrl(url)) {
                    continue
                }
                Log.d("Main", url)
                doAsync {
                    val configText = URL(url).readText()
                    uiThread {
                        importBatchConfig(Utils.decode(configText), id)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.title_file_chooser)),
                    REQUEST_FILE_CHOOSER)
        } catch (ex: android.content.ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        RxPermissions(this)
                .request(Manifest.permission.READ_EXTERNAL_STORAGE)
                .subscribe {
                    if (it) {
                        try {
                            val inputStream = contentResolver.openInputStream(uri)
                            val configText = inputStream!!.bufferedReader().readText()
                            importCustomizeConfig(configText)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else
                        toast(R.string.toast_permission_denied)
                }
    }

    /**
     * import customize config
     */
    private fun importCustomizeConfig(server: String?) {
        if (server == null) {
            return
        }
        if (!V2rayConfigUtil.isValidConfig(server)) {
            toast(R.string.toast_config_file_invalid)
            return
        }
        val resId = AngConfigManager.importCustomizeConfig(server)
        if (resId > 0) {
            toast(resId)
        } else {
            toast(R.string.toast_success)
            adapter.updateConfigList()
        }
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    private
    var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(activity: MainActivity) : BroadcastReceiver() {
        internal var mReference: SoftReference<MainActivity> = SoftReference(activity)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val activity = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    activity?.isRunning = true
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    activity?.isRunning = false
                }
                AppConfig.MSG_TEST_SUCCESS -> {
                    var mixmsg= intent.getStringExtra("content")
                    var msg = mixmsg!!.split(",")
                    configs.vmess[msg[0].toInt()].testResult=msg[1]
                    activity?.adapter?.updateSelectedItem(msg[0].toInt())
                    //activity?.isRunning = true
                }
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    activity?.toast(R.string.toast_services_success)
                    activity?.isRunning = true
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    activity?.toast(R.string.toast_services_failure)
                    activity?.isRunning = false
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    activity?.isRunning = false
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun showCircle() {
        fabProgressCircle?.show()
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        if (fabProgressCircle.isShown) {
                            fabProgressCircle?.hide()
                        }
                    }
        } catch (e: Exception) {
        }
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity<SubSettingActivity>()
            }
            R.id.settings -> {
                startActivity<SettingsActivity>("isRunning" to isRunning)
            }
            R.id.feedback -> {
                Utils.openUri(this, AppConfig.v2rayNGIssues)
            }
            R.id.promotion -> {
                Utils.openUri(this, getString(R.string.promotionUrl))
            }
            R.id.donate -> {
                Utils.openUri(this, AppConfig.aboutUrl)
//                startActivity<InappBuyActivity>()
            }
            R.id.logcat -> {
                startActivity<LogcatActivity>()
            }
        }
        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }
}