// FILE: ./app/src/main/java/com/asterisk/voip/MainActivity.kt
package com.asterisk.voip

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.asterisk.voip.databinding.ActivityMainBinding
import com.asterisk.voip.service.AsteriskService
import com.asterisk.voip.utils.DatabaseInitializer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    var asteriskService: AsteriskService? = null
        private set
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AsteriskService.AsteriskBinder
            asteriskService = binder.getService()
            serviceBound = true
            android.util.Log.d("MainActivity", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            asteriskService = null
            serviceBound = false
            android.util.Log.d("MainActivity", "Service disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            android.util.Log.d("MainActivity", "All permissions granted")
            startAndBindService()
        } else {
            android.util.Log.w("MainActivity", "Some permissions denied: $permissions")
            // Still start service even if some permissions are denied
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        android.util.Log.d("MainActivity", "onCreate")

        setupNavigation()
        initializeDefaultData()
        checkAndRequestPermissions()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)

        android.util.Log.d("MainActivity", "Navigation setup complete")
    }

    private fun initializeDefaultData() {
        lifecycleScope.launch {
            try {
                val app = application as AsteriskApplication
                DatabaseInitializer.initializeDefaultDialPlan(app.repository)
                android.util.Log.d("MainActivity", "Default dial plan initialized")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error initializing default data: ${e.message}", e)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Post Notifications permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Record Audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Requesting permissions: $permissions")
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            android.util.Log.d("MainActivity", "All permissions already granted")
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        try {
            val serviceIntent = Intent(this, AsteriskService::class.java)

            // Start service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
                android.util.Log.d("MainActivity", "Started foreground service")
            } else {
                startService(serviceIntent)
                android.util.Log.d("MainActivity", "Started service")
            }

            // Bind to service
            val bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            android.util.Log.d("MainActivity", "Bind service result: $bound")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting/binding service: ${e.message}", e)
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("MainActivity", "onStart")
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("MainActivity", "onResume")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("MainActivity", "onPause")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MainActivity", "onDestroy")

        if (serviceBound) {
            try {
                unbindService(serviceConnection)
                serviceBound = false
                android.util.Log.d("MainActivity", "Service unbound")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error unbinding service: ${e.message}", e)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}