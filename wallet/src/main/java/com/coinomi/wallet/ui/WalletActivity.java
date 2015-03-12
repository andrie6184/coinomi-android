package com.coinomi.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.coinomi.core.coins.CoinID;
import com.coinomi.core.coins.CoinType;
import com.coinomi.core.uri.CoinURI;
import com.coinomi.core.uri.CoinURIParseException;
import com.coinomi.core.wallet.WalletAccount;
import com.coinomi.wallet.Constants;
import com.coinomi.wallet.R;
import com.coinomi.wallet.service.CoinService;
import com.coinomi.wallet.service.CoinServiceImpl;
import com.coinomi.wallet.tasks.CheckUpdateTask;
import com.coinomi.wallet.util.Keyboard;
import com.coinomi.wallet.util.SystemUtils;

import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author John L. Jegutanis
 * @author Andreas Schildbach
 */
final public class WalletActivity extends AbstractWalletActionBarActivity implements
        NavigationDrawerFragment.NavigationDrawerCallbacks, BalanceFragment.Listener {
    private static final Logger log = LoggerFactory.getLogger(WalletActivity.class);

    private static final int RECEIVE = 0;
    private static final int BALANCE = 1;
    private static final int SEND = 2;

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int ADD_COIN = 1;

    private static final int TX_BROADCAST_OK = 0;
    private static final int TX_BROADCAST_ERROR = 1;

    /**
     * Fragment managing the behaviors, interactions and presentation of the navigation drawer.
     */
    private NavigationDrawerFragment mNavigationDrawerFragment;

    /**
     * Used to store the last screen title. For use in {@link #restoreActionBar()}.
     */
    private CharSequence mTitle;

    private int coinIconRes = R.drawable.ic_launcher;

    /**
     * For SharedPreferences, used to check if first launch ever.
     */
    private ViewPager mViewPager;
    private CoinType currentType;
    private String currentAccountId;
    private Intent connectCoinIntent;

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TX_BROADCAST_OK:
                    Toast.makeText(WalletActivity.this, getString(R.string.sent_msg),
                            Toast.LENGTH_LONG).show();
                    goToBalance();
                    break;
                case TX_BROADCAST_ERROR:
                    Toast.makeText(WalletActivity.this, getString(R.string.get_tx_broadcast_error),
                            Toast.LENGTH_LONG).show();
                    goToBalance();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet);

        if (getWalletApplication().getWallet() == null) {
            startIntro();
            finish();
            return;
        }

        if (getIntent().getBooleanExtra(Constants.ARG_TEST_WALLET, false)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.test_wallet)
                    .setMessage(R.string.test_wallet_message)
                    .setNeutralButton(R.string.button_ok, null)
                    .create().show();
        }

        if (savedInstanceState == null) {
            checkAlerts();
        }

        mTitle = getTitle();

//        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);
        getSupportActionBar().setElevation(0);

        mNavigationDrawerFragment = (NavigationDrawerFragment)
                getSupportFragmentManager().findFragmentById(R.id.navigation_drawer);
        // Set up the drawer.
        mNavigationDrawerFragment.setUp(
                R.id.navigation_drawer,
                (DrawerLayout) findViewById(R.id.drawer_layout));

        // Set up the ViewPager, attaching the adapter and setting up a listener for when the
        // user swipes between sections.
        mViewPager = (ViewPager) findViewById(R.id.pager);
        // Set OffscreenPageLimit to 2 because receive fragment draws a QR code and we don't
        // want to re-render that if we go to the SendFragment and back
        mViewPager.setOffscreenPageLimit(2);
        mViewPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override public void onPageScrolled(int pos, float posOffset, int posOffsetPixels) { }

            @Override
            public void onPageSelected(int position) {
                if (position == BALANCE) Keyboard.hideKeyboard(WalletActivity.this);
            }

            @Override public void onPageScrollStateChanged(int state) {}
        });

        // Get the last used wallet pocket and select it
        CoinType lastPocket = getWalletApplication().getConfiguration().getLastPocket();
        mNavigationDrawerFragment.selectCoinInit(lastPocket);
    }

    @Override
    protected void onResume() {
        super.onResume();

        getWalletApplication().startBlockchainService(CoinService.ServiceMode.CANCEL_COINS_RECEIVED);
        connectCoinService();
        //TODO
//        checkLowStorageAlert();
    }


    @Override
    public void onLocalAmountClick() {
        startExchangeRates();
    }

    @Override
    public void onTransactionBroadcastSuccess(WalletAccount pocket, Transaction transaction) {
        handler.sendMessage(handler.obtainMessage(TX_BROADCAST_OK, transaction));
    }

    @Override
    public void onTransactionBroadcastFailure(WalletAccount pocket, Transaction transaction) {
        handler.sendMessage(handler.obtainMessage(TX_BROADCAST_ERROR, transaction));
    }

    @Override
    public void onAccountSelected(WalletAccount account) {
        log.info("Coin selected {}", account.getId());

        openPocket(account);
    }

    @Override
    public void onNavigationDrawerAddCoinsSelected() {
        startActivityForResult(new Intent(WalletActivity.this, AddCoinsActivity.class), ADD_COIN);
    }

    private void openPocket(WalletAccount account) {
        if (mViewPager != null && !account.getId().equals(currentAccountId)) {
            currentAccountId = account.getId();
            currentType = account.getCoinType();
            mTitle = currentType.getName();
            coinIconRes = Constants.COINS_ICONS.get(currentType);
            AppSectionsPagerAdapter adapter = new AppSectionsPagerAdapter(this, account);
            mViewPager.setAdapter(adapter);
            mViewPager.setCurrentItem(BALANCE);
            mViewPager.getAdapter().notifyDataSetChanged();
            getWalletApplication().getConfiguration().touchLastPocket(currentType);
            connectCoinService();
        }
    }

    private void connectCoinService() {
        if (connectCoinIntent == null) {
            connectCoinIntent = new Intent(CoinService.ACTION_CONNECT_COIN, null,
                    getWalletApplication(), CoinServiceImpl.class);
        }
        // Open connection if needed or possible
        connectCoinIntent.putExtra(Constants.ARG_ACCOUNT_ID, currentAccountId);
        getWalletApplication().startService(connectCoinIntent);
    }

    public void restoreActionBar() {
        ActionBar actionBar = getSupportActionBar();

//        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        actionBar.setDisplayShowTitleEnabled(true);
//        actionBar.setIcon(coinIconRes);
        actionBar.setTitle(mTitle);
    }

    private void checkAlerts() {
        // If not store version, show update dialog if needed
        if (!SystemUtils.isStoreVersion(this)) {
            final PackageInfo packageInfo = getWalletApplication().packageInfo();
            new CheckUpdateTask() {
                @Override
                protected void onPostExecute(Integer serverVersionCode) {
                    if (serverVersionCode != null && serverVersionCode > packageInfo.versionCode) {
                        showUpdateDialog();
                    }
                }
            }.execute();
        }
    }

    private void showUpdateDialog() {

        final PackageManager pm = getPackageManager();
//        final Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Constants.MARKET_APP_URL, getPackageName())));
        final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL));

        final AlertDialog.Builder builder = new AlertDialog.Builder(WalletActivity.this);
        builder.setTitle(R.string.wallet_update_title);
        builder.setMessage(R.string.wallet_update_message);

        // Disable market link for now
//        if (pm.resolveActivity(marketIntent, 0) != null)
//        {
//            builder.setPositiveButton("Play Store", new DialogInterface.OnClickListener() {
//                @Override
//                public void onClick(final DialogInterface dialog, final int id) {
//                    startActivity(marketIntent);
//                    finish();
//                }
//            });
//        }

        if (pm.resolveActivity(binaryIntent, 0) != null)
        {
            builder.setNeutralButton(R.string.button_download, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    startActivity(binaryIntent);
                    finish();
                }
            });
        }

        builder.setNegativeButton(R.string.button_dismiss, null);
        builder.create().show();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                try {
                    final CoinURI coinUri = new CoinURI(input);
                    CoinType scannedType = coinUri.getType();

                    if (!Constants.SUPPORTED_COINS.contains(scannedType)) {
                        String error = getResources().getString(R.string.unsupported_coin, scannedType.getName());
                        throw new CoinURIParseException(error);
                    } else if (!getWalletApplication().isAccountExists(scannedType)) {
                        String error = getResources().getString(R.string.coin_not_added, scannedType.getName());
                        throw new CoinURIParseException(error);
                    }

                    setSendFromCoin(coinUri);
                } catch (final CoinURIParseException e) {
                    String error = getResources().getString(R.string.uri_error, e.getMessage());
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == ADD_COIN) {
            if (resultCode == Activity.RESULT_OK) {
                // TODO
                String accountId = intent.getStringExtra(Constants.ARG_ACCOUNT_ID);
                WalletAccount account = getWalletApplication().getWallet().getAccount(accountId);
                mNavigationDrawerFragment.notifyDataSetChanged();
                mNavigationDrawerFragment.selectItem(account.getCoinType());
            }
        }
    }

    private void setSendFromCoin(CoinURI coinUri) throws CoinURIParseException {
        mNavigationDrawerFragment.selectItem(coinUri.getType());
        if (mViewPager != null) {
            mViewPager.setCurrentItem(SEND);

            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof SendFragment) {
                    ((SendFragment) fragment)
                            .updateStateFrom(coinUri.getAddress(), coinUri.getAmount(), coinUri.getLabel());
                    break;
                }
            }
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mNavigationDrawerFragment != null && !mNavigationDrawerFragment.isDrawerOpen()) {
            // Only show items in the action bar relevant to this screen
            // if the drawer is not showing. Otherwise, let the drawer
            // decide what to show in the action bar.
            getMenuInflater().inflate(R.menu.global, menu);
            restoreActionBar();
            return true;
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(WalletActivity.this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_scan_qr_code) {
            startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_CODE_SCAN);
            return true;
        } else if (id == R.id.action_refresh_wallet) {
            refreshWallet();
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(WalletActivity.this, AboutActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    void startExchangeRates() {
        if (currentType != null) {
            Intent intent = new Intent(this, ExchangeRatesActivity.class);
            intent.putExtra(Constants.ARG_COIN_ID, currentType.getId());
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.no_wallet_pocket_selected, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshWallet() {
        if (getWalletApplication().getWallet() != null) {
            Intent intent = new Intent(CoinService.ACTION_RESET_WALLET, null,
                    getWalletApplication(), CoinServiceImpl.class);
            intent.putExtra(Constants.ARG_ACCOUNT_ID, currentAccountId);
            getWalletApplication().startService(intent);
            // FIXME, we get a crash if the activity is not restarted
            Intent introIntent = new Intent(WalletActivity.this, WalletActivity.class);
            startActivity(introIntent);
            finish();
        }
    }

    private void startIntro() {
        Intent introIntent = new Intent(this, IntroActivity.class);
        startActivity(introIntent);
    }

    private void startRestore() {
        startActivity(new Intent(this, IntroActivity.class));
    }

    @Override
    public void onBackPressed() {
        if (mNavigationDrawerFragment != null && mNavigationDrawerFragment.isDrawerOpen()) {
            mNavigationDrawerFragment.closeDrawer();
            return;
        }

        // If not in balance screen, back button brings us there
        boolean screenChanged = goToBalance();
        if (!screenChanged) {
            super.onBackPressed();
        }
    }

    private boolean goToBalance() {
        if (mViewPager != null && mViewPager.getCurrentItem() != BALANCE) {
            mViewPager.setCurrentItem(BALANCE);
            return true;
        }
        return false;
    }

    private static class AppSectionsPagerAdapter extends FragmentStatePagerAdapter {

        private final WalletActivity walletActivity;
        private final String accountId;
        private AddressRequestFragment request;
        private SendFragment send;
        private BalanceFragment balance;

        public AppSectionsPagerAdapter(WalletActivity walletActivity, WalletAccount account) {
            super(walletActivity.getSupportFragmentManager());
            this.walletActivity = walletActivity;
            this.accountId = account.getId();
        }

        @Override
        public Fragment getItem(int i) {
            switch (i) {
                case RECEIVE:
                    if (request == null) request = AddressRequestFragment.newInstance(accountId);
                    return request;
                case SEND:
                    if (send == null) send = SendFragment.newInstance(accountId);
                    return send;
                case BALANCE:
                default:
                    if (balance == null) balance = BalanceFragment.newInstance(accountId);
                    return balance;
            }
        }

        @Override
        public int getCount() {
            return 3;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case RECEIVE:
                    return walletActivity.getString(R.string.wallet_title_request);
                case SEND:
                    return walletActivity.getString(R.string.wallet_title_send);
                case BALANCE:
                default:
                    return walletActivity.getString(R.string.wallet_title_balance);
            }
        }
    }
}
