/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsdawareapplication

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.M8Model
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsdawareapplication.network.M8InterfaceApi
import com.fivegmag.a5gmsmediastreamhandler.ExoPlayerAdapter
import com.fivegmag.a5gmsmediastreamhandler.MediaSessionHandlerAdapter
import androidx.media3.ui.PlayerView
import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import org.greenrobot.eventbus.EventBus
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.InputStream
import java.net.URI
import java.util.*


const val TAG_AWARE_APPLICATION = "5GMS Aware Application"

@UnstableApi
class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    private val mediaSessionHandlerAdapter = MediaSessionHandlerAdapter()
    private val exoPlayerAdapter = ExoPlayerAdapter()
    private val mediaStreamHandlerEventHandler = MediaStreamHandlerEventHandler()
    private var currentSelectedStreamIndex: Int = 0
    private lateinit var currentSelectedM8Key: String
    private lateinit var m8InterfaceApi: M8InterfaceApi
    private lateinit var m8Data: M8Model
    private lateinit var exoPlayerView: PlayerView
    private lateinit var configProperties: Properties

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            loadConfiguration()
            populateM8SelectionSpinner()
            exoPlayerView = findViewById(R.id.idExoPlayerVIew)
            setApplicationVersionNumber()
            printDependenciesVersionNumbers()
            registerButtonListener()
            mediaSessionHandlerAdapter.initialize(
                this,
                exoPlayerAdapter,
                ::onConnectionToMediaSessionHandlerEstablished
            )
            val representationInfoTextView = findViewById<TextView>(R.id.representationInfo)
            mediaStreamHandlerEventHandler.initialize(representationInfoTextView, this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        EventBus.getDefault().unregister(mediaStreamHandlerEventHandler)
        super.onStop()
        // Unbind from the service
        mediaSessionHandlerAdapter.reset(this)
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(mediaStreamHandlerEventHandler)
    }

    private fun setApplicationVersionNumber() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionTextView = findViewById<TextView>(R.id.versionNumber)
            val versionText = getString(R.string.versionTextField, versionName)
            versionTextView.text = versionText
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun printDependenciesVersionNumbers() {
        Log.d(TAG_AWARE_APPLICATION, "5GMS Common Library Version: ${BuildConfig.LIB_VERSION_a5gmscommonlibrary}")
        Log.d(TAG_AWARE_APPLICATION, "5GMS Media Stream Handler Version: ${BuildConfig.LIB_VERSION_a5gmsmediastreamhandler}")
    }

    private fun loadConfiguration() {
        try {
            val inputStream: InputStream = this.assets.open("config.properties.xml")
            configProperties = Properties()
            configProperties.loadFromXML(inputStream)
            inputStream.close()
        } catch (e: Exception) {

        }
    }

    private fun populateM8SelectionSpinner() {
        try {
            val spinner: Spinner = findViewById(R.id.idM8Spinner)
            val spinnerOptions: ArrayList<String> = ArrayList()
            val propertyNames = configProperties.propertyNames()

            while (propertyNames.hasMoreElements()) {
                spinnerOptions.add(propertyNames.nextElement() as String)
            }

            val adapter: ArrayAdapter<String> = ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item, spinnerOptions
            )
            spinner.adapter = adapter
            spinner.onItemSelectedListener = this
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initializeRetrofitForM8InterfaceApi(url: String) {
        val retrofitM8Interface: Retrofit = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        m8InterfaceApi =
            retrofitM8Interface.create(M8InterfaceApi::class.java)
    }

    private fun onConnectionToMediaSessionHandlerEstablished() {
        exoPlayerAdapter.initialize(exoPlayerView, this, mediaSessionHandlerAdapter)
    }

    private fun registerButtonListener() {
        findViewById<Button>(R.id.loadButton)
            .setOnClickListener {
                loadStream()
            }
    }

    private fun onM8DataChanged() {
        mediaSessionHandlerAdapter.setM5Endpoint(m8Data.m5BaseUrl)
        populateStreamSelectionSpinner()
    }

    private fun populateStreamSelectionSpinner() {
        try {
            val spinner: Spinner = findViewById(R.id.idStreamSpinner)
            val spinnerOptions: ArrayList<String> = ArrayList()

            val iterator = m8Data.serviceList.iterator()
            while (iterator.hasNext()) {
                spinnerOptions.add(iterator.next().name)
            }
            val adapter: ArrayAdapter<String> = ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item, spinnerOptions
            )
            spinner.adapter = adapter
            spinner.onItemSelectedListener = this
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadStream() {
        exoPlayerAdapter.stop()
        val serviceListEntry: ServiceListEntry = m8Data.serviceList[currentSelectedStreamIndex]
        mediaSessionHandlerAdapter.initializePlaybackByServiceListEntry(serviceListEntry)
    }

    private fun replaceDoubleTicks(value: String): String {
        return value.replace("\"", "")
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (parent != null) {
            when (parent.id) {
                R.id.idStreamSpinner -> {
                    currentSelectedStreamIndex = position
                }

                R.id.idM8Spinner -> {
                    currentSelectedM8Key = parent.selectedItem as String
                    val selectedUri = URI(configProperties.getProperty(currentSelectedM8Key))
                    if (selectedUri.isAbsolute) {
                        setM8DataViaEndpoint(selectedUri.toString())
                    } else {
                        setM8DataViaJson(selectedUri.toString())
                    }
                }

                else -> { // Note the block
                }
            }
        }
    }

    private fun setM8DataViaEndpoint(m8HostingEndpoint: String) {
        try {
            initializeRetrofitForM8InterfaceApi(m8HostingEndpoint)
            val call: Call<ResponseBody>? =
                m8InterfaceApi.fetchServiceAccessInformationList()
            call?.enqueue(object : Callback<ResponseBody?> {
                override fun onResponse(
                    call: Call<ResponseBody?>,
                    response: Response<ResponseBody?>
                ) {
                    val resource: String? = response.body()?.string()
                    if (resource != null) {
                        val jsonObject: JsonObject =
                            Json.parseToJsonElement(resource).jsonObject
                        val m5BaseUrl: String =
                            replaceDoubleTicks(jsonObject.get("m5BaseUrl").toString())
                        val jsonServiceList = jsonObject.get("serviceList")?.jsonArray
                        m8Data = jsonServiceList?.let { createM8Model(m5BaseUrl, it) }!!
                        onM8DataChanged()
                    }
                }

                override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                    call.cancel()
                }
            })
        } catch (_: Exception) {

        }
    }

    private fun setM8DataViaJson(url: String) {
        val json: String?
        try {
            val inputStream: InputStream = assets.open(url)
            json = inputStream.bufferedReader().use { it.readText() }
            val jsonObject: JsonObject = Json.parseToJsonElement(json).jsonObject
            val m5BaseUrl: String = replaceDoubleTicks(jsonObject.get("m5BaseUrl").toString())
            val jsonServiceList = jsonObject.get("serviceList")?.jsonArray
            m8Data = jsonServiceList?.let { createM8Model(m5BaseUrl, it) }!!
            onM8DataChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createM8Model(m5BaseUrl: String, jsonServiceList: JsonArray): M8Model {
        val serviceList = ArrayList<ServiceListEntry>()

        for (serviceListEntry in jsonServiceList) {
            val itemAsJsonObject = Json.parseToJsonElement(serviceListEntry.toString()).jsonObject
            val name: String =
                replaceDoubleTicks(itemAsJsonObject["name"].toString())
            val provisioningSessionId =
                replaceDoubleTicks(itemAsJsonObject["provisioningSessionId"].toString())

            val entryPoints = ArrayList<EntryPoint>()
            val entryPointList = itemAsJsonObject.get("entryPoints")?.jsonArray

            if (entryPointList != null) {
                for (entryPoint in entryPointList) {
                    val entryPointAsJsonObject =
                        Json.parseToJsonElement(entryPoint.toString()).jsonObject
                    val locator = replaceDoubleTicks(entryPointAsJsonObject["locator"].toString())
                    val contentType =
                        replaceDoubleTicks(entryPointAsJsonObject["contentType"].toString())
                    val profiles = ArrayList<String>()
                    val profileList = entryPointAsJsonObject.get("profiles")?.jsonArray
                    if (profileList != null) {
                        for (profileEntry in profileList) {
                            profiles.add(profileEntry.toString())
                        }
                    }
                    entryPoints.add(
                        EntryPoint(
                            locator,
                            contentType,
                            profiles
                        )
                    )
                }
            }
            val entry = ServiceListEntry(
                provisioningSessionId,
                name,
                entryPoints
            )
            serviceList.add(entry)
        }

        return M8Model(m5BaseUrl, serviceList)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        TODO("Not yet implemented")
    }
}