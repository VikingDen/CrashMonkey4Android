package com.android.cts.tradefed.testtype;

import java.io.File;
import java.util.List;

import com.android.chimpchat.adb.AdbChimpDevice;
import com.android.cts.tradefed.build.CtsBuildHelper;
import com.android.cts.tradefed.device.DeviceInfoCollector;
import com.android.cts.tradefed.device.DeviceNetWorkListener;
import com.android.cts.tradefed.device.DeviceUnlock;
import com.android.cts.tradefed.device.MonkeyActivityListener;
import com.android.cts.tradefed.result.CtsXmlResultReporter;
import com.android.cts.tradefed.testtype.monkey.Monkey;
import com.android.cts.tradefed.testtype.monkey.MonkeySourceRandom;
import com.android.ddmlib.Log;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;

public class MonkeyTest implements IDeviceTest, IResumableTest, IBuildReceiver {
	private static final String LOG_TAG = "MonkeyTest";

	public static final String RUNNINT_SCREENSHOT = "screenshot";
	public static final String FINAL_SCREENSHOT = "final";

	@Option(name = "p", description = "指定的包名，如果没有指定，就从手机的当前界面开始")
	private String mPackage = "";
	@Option(name = "v", description = "注入的事件数，默认为1000次")
	private int mInjectEvents = 1000;
	@Option(name = "throttle", description = "每一个事件的间隔时间，默认为300毫秒，")
	private int mThrottle = 300;

	@Option(name = "pct-tap", description = "点击事件的百分比")
	private float mTouchPct = 0.0f;
	@Option(name = "pct-motion", description = "滑动事件的百分比")
	private float mMotionPct = 0.0f;
	@Option(name = "pct-nav", description = "基本导航事件的百分比")
	private float mNavPct = 0.0f;
	@Option(name = "pct-majornav", description = "主要导航事件的百分比")
	private float mMajorNavPct = 0.0f;
	@Option(name = "pct-syskeys", description = "系统事件的百分比")
	private float mSyskeysPct = 0.0f;
	@Option(name = "pct-drag", description = "系统事件的百分比")
	private float mDrag = 0.0f;
	@Option(name = "logcat-size", description = "The max number of logcat data in bytes to capture when --logcat-on-failure is on. "
			+ "Should be an amount that can comfortably fit in memory.")
	private int mMaxLogcatBytes = 20 * 1024; // 500K

	@Option(name = "plan", description = "the test plan to run.", importance = Importance.IF_UNSET)
	private String mPlanName = null;
	@Option(name = "reboot", description = "Do not reboot device after running some amount of tests. Default behavior is to reboot.")
	private boolean reboot = false;
	@Option(name = "skip-device-info", description = "flag to control whether to collect info from device. Providing this flag will speed up "
			+ "test execution for short test runs but will result in required data being omitted from "
			+ "the test report.")
	private boolean mSkipDeviceInfo = false;

	@Option(name = "skip-device-unlock", description = "跳过解锁步骤")
	private boolean mSkipDeviceUnlock = false;

	@Option(name = "app-path", description = "指定app本地路径")
	private String mAppPath = null;

	@Option(name = "a", description = "指定app的主activity")
	private String mActivity = null;
	@Option(name = "wifiSsdk", description = "wifi用户名")
	private String mWifiSSID = null;
	@Option(name = "wifiPsk", description = "wifi密码")
	private String mWifiPsk = null;

	@Option(name = "skip-uninstall-app", description = "wifi密码")
	private boolean mSkipUninstallApp = false;

	private ITestDevice mDevice = null;
	private AdbChimpDevice mACDevice = null;
	private ITestInvocationListener mListener = null;
	private CtsXmlResultReporter ctsXmlResultReporter = null;

	private CtsBuildHelper mCtsBuild = null;
	private IBuildInfo mBuildInfo = null;

	private MonkeyActivityListener monkeyActivityListener;

	private DeviceNetWorkListener monkeyNetWorkListener;

	@Override
	public void setDevice(ITestDevice device) {
		// TODO Auto-generated method stub
		this.mDevice = device;
	}

	@Override
	public ITestDevice getDevice() {
		// TODO Auto-generated method stub
		return mDevice;
	}

	private void parseCtsXmlResultReporter() {
		if (mListener instanceof ResultForwarder) {
			ResultForwarder forwarder = (ResultForwarder) mListener;
			List<ITestInvocationListener> listeners = forwarder.getListeners();
			for (ITestInvocationListener listen : listeners) {
				if (listen instanceof CtsXmlResultReporter) {
					ctsXmlResultReporter = (CtsXmlResultReporter) listen;
					return;
				}
			}
		}
	}

	@Override
	public void run(ITestInvocationListener listener)
			throws DeviceNotAvailableException {

		CLog.i(String.format("Monkey Test for device %s", getDevice()
				.getSerialNumber()));
		mListener = listener;
		check();
		if (reboot) {
			rebootDevice();
		}

		collectDeviceInfo(getDevice(), mCtsBuild, listener);
		// 屏幕解锁
		unlockDevice(getDevice(), mCtsBuild);

		// 安装应用
		installPackage();
		// 启动应用
		launchApp();
		parseCtsXmlResultReporter();
		float[] mFactors = parseFactors();

		initListener();
		try {
			Monkey monkey = new Monkey(mPackage, getChimpDevice(), mFactors);
			for (int i = 0; i < mInjectEvents; i++) {
				saveScreenshot(RUNNINT_SCREENSHOT);
				monkey.nextRandomEvent(ctsXmlResultReporter);
				// saveLogcat();
				Thread.sleep(mThrottle);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			exitAdbChimpDevice();
		}
		// 保存最后的现场截图
		saveScreenshot(FINAL_SCREENSHOT);
		// 卸载应用
		uninstallPackage();
		destoryListener();
	}

	private void destoryListener() {
		if (monkeyNetWorkListener != null)
			monkeyNetWorkListener.setRunning(false);
		if (monkeyActivityListener != null)
			monkeyActivityListener.setRunning(false);

	}

	private void initListener() {
		// 确保网络连接成功
		if (mWifiSSID != null && mWifiPsk != null) {
			monkeyNetWorkListener = new DeviceNetWorkListener(getDevice(),
					mWifiSSID, mWifiPsk);
			new Thread(monkeyNetWorkListener).start();
		}

		// 启动监听线程，确保app处于活动状态
		if (mPackage != null && mActivity != null) {
			monkeyActivityListener = new MonkeyActivityListener(getDevice(),
					mPackage, mActivity);
			new Thread(monkeyActivityListener).start();
		}

	}

	private void check() {
		if (mPlanName == null || !mPlanName.equals("Monkey")) {
			throw new IllegalArgumentException("Require plan : Monkey !");
		}
		if (mListener == null) {
			throw new IllegalArgumentException("illegal listener");
		}
		checkAppPath();

	}

	// 检查应用地址是否正确
	private void checkAppPath() {
		if (mAppPath == null)
			return;
		File file = new File(mAppPath);
		if (!file.exists()) {
			throw new IllegalArgumentException(String.format(
					"File of [%s] does not exist", mAppPath));
		}
		if (mPackage == null || mActivity == null) {
			throw new IllegalArgumentException(
					String.format("you provider '-app-path',but you don't provide '-p' or '-a'"));
		}

	}

	// 安装应用
	private void installPackage() throws DeviceNotAvailableException {
		if (mAppPath == null)
			return;
		CLog.i("Attempting to install %s on %s ", mAppPath, getDevice()
				.getSerialNumber());
		getDevice().installPackage(new File(mAppPath), true);

	}

	// 卸载应用,如果没有指定-p参数，说明不指定应用，那么就无需启动
	private void uninstallPackage() throws DeviceNotAvailableException {
		if (mAppPath == null && !mSkipUninstallApp)
			return;
		CLog.i("Attempting to uninstall %s on %s  ", mPackage, getDevice()
				.getSerialNumber());
		getDevice().uninstallPackage(mPackage);

	}

	// 启动应用
	private void launchApp() throws DeviceNotAvailableException {
		if (mPackage == null || mActivity == null)
			return;
		String cmd = "am start -W " + mPackage + "/" + mActivity;
		CLog.i("Attempting to launch %s on %s using command [%s] ", mPackage,
				getDevice().getSerialNumber(), cmd);
		String result = getDevice().executeShellCommand(cmd);
		if (result.contains("does not exist") || result.contains("Error")) {
			throw new IllegalArgumentException(String.format("%s", result));
		}

	}

	// 组装各个monkey事件的比重
	private float[] parseFactors() {
		float[] mFactors = new float[MonkeySourceRandom.FACTORZ_COUNT];
		mFactors[MonkeySourceRandom.FACTOR_TOUCH] = mTouchPct;
		mFactors[MonkeySourceRandom.FACTOR_MOTION] = mMotionPct;
		mFactors[MonkeySourceRandom.FACTOR_NAV] = mNavPct;
		mFactors[MonkeySourceRandom.FACTOR_MAJORNAV] = mMajorNavPct;
		mFactors[MonkeySourceRandom.FACTOR_SYSOPS] = mSyskeysPct;
		mFactors[MonkeySourceRandom.FACTOR_DRAG] = mDrag;
		return mFactors;
	}

	// 截图
	private void saveScreenshot(String preffix) {
		long current = System.currentTimeMillis();
		long time = 0;
		try {
			InputStreamSource screenSource = mDevice.getScreenshot();
			mListener.testLog(
					String.format("%s-%s", preffix,
							TimeUtil.getTimestampForFile()), LogDataType.PNG,
					screenSource);
			screenSource.cancel();
		} catch (DeviceNotAvailableException e) {
			// TODO: rethrow this somehow
			CLog.e("Device %s became unavailable while capturing screenshot, %s",
					mDevice.getSerialNumber(), e.toString());
		}
		time = System.currentTimeMillis() - current;
		CLog.i(String.format("Cost time %d ms in screenshot", time));

	}

	// 保存日志
	private void saveLogcat() {
		RunUtil.getDefault().sleep(10);
		InputStreamSource logSource = mDevice.getLogcat(mMaxLogcatBytes);
		mListener.testLog(
				String.format("logcat-%s", TimeUtil.getTimestampForFile()),
				LogDataType.TEXT, logSource);
		logSource.cancel();
	}

	private AdbChimpDevice getChimpDevice() {
		if (mACDevice == null) {
			mACDevice = new AdbChimpDevice(getDevice().getIDevice());
		}
		return mACDevice;
	}

	/**
	 * 退出ChimpDevice
	 */
	private void exitAdbChimpDevice() {
		if (mACDevice == null)
			return;
		mACDevice.dispose();
	}

	@Override
	public boolean isResumable() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void setBuild(IBuildInfo buildInfo) {
		mCtsBuild = CtsBuildHelper.createBuildHelper(buildInfo);
		mBuildInfo = buildInfo;
	}

	void collectDeviceInfo(ITestDevice device, CtsBuildHelper ctsBuild,
			ITestInvocationListener listener)
			throws DeviceNotAvailableException {
		if (!mSkipDeviceInfo)
			DeviceInfoCollector.collectDeviceInfo(device,
					ctsBuild.getTestCasesDir(), listener);
	}

	void unlockDevice(ITestDevice device, CtsBuildHelper ctsBuild)
			throws DeviceNotAvailableException {
		if (!mSkipDeviceUnlock)
			DeviceUnlock.unlockDevice(device, ctsBuild.getTestCasesDir());
	}

	private void rebootDevice() throws DeviceNotAvailableException {
		final int TIMEOUT_MS = 5 * 60 * 1000;
		TestDeviceOptions options = mDevice.getOptions();
		// store default value and increase time-out for reboot
		int rebootTimeout = options.getRebootTimeout();
		long onlineTimeout = options.getOnlineTimeout();
		options.setRebootTimeout(TIMEOUT_MS);
		options.setOnlineTimeout(TIMEOUT_MS);
		mDevice.setOptions(options);

		mDevice.reboot();

		// restore default values
		options.setRebootTimeout(rebootTimeout);
		options.setOnlineTimeout(onlineTimeout);
		mDevice.setOptions(options);
		Log.i(LOG_TAG, "Rebooting done");
		try {
			Thread.sleep(TIMEOUT_MS / 10);
		} catch (InterruptedException e) {
			Log.i(LOG_TAG, "Boot wait interrupted");
		}
		// mDevice.connectToWifiNetwork(mWifiSSID, mWifiPsk);
	}

}
