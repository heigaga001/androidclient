/*
 * Kontalk Android client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.afollestad.materialdialogs.AlertDialogWrapper;
import com.afollestad.materialdialogs.MaterialDialog;

import org.jivesoftware.smack.util.StringUtils;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ListAdapter;
import android.widget.Toast;

import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.data.Contact;
import org.kontalk.data.Conversation;
import org.kontalk.provider.KontalkGroupCommands;
import org.kontalk.provider.MessagesProvider;
import org.kontalk.provider.MyMessages;
import org.kontalk.provider.MyMessages.Threads;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.sync.Syncer;
import org.kontalk.ui.prefs.PreferencesActivity;
import org.kontalk.ui.view.ContactPickerListener;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;
import org.kontalk.util.XMPPUtils;


/**
 * The conversations list activity.
 *
 * Layout is a sliding pane holding the conversation list as primary view and the contact list as
 * browser side view.
 *
 * @author Daniele Ricci
 * @version 1.0
 */
public class ConversationsActivity extends MainActivity
        implements ContactPickerListener, ComposeMessageParent {
    public static final String TAG = ConversationsActivity.class.getSimpleName();

    private ConversationListFragment mFragment;

    /** Search menu item. */
    private MenuItem mSearchMenu;
    private MenuItem mDeleteAllMenu;
    /** Offline mode menu item. */
    private MenuItem mOfflineMenu;

    private static final int REQUEST_CONTACT_PICKER = 7720;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.conversations_screen);

        setupToolbar(false);

        mFragment = (ConversationListFragment) getSupportFragmentManager()
                .findFragmentById(R.id.fragment_conversation_list);

        if (!afterOnCreate())
            handleIntent(getIntent());
    }

    /** Called when a new intent is sent to the activity (if already started). */
    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent);

        ConversationListFragment fragment = getListFragment();
        fragment.startQuery();
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();

            // this is for intents coming from the world, forwarded by ComposeMessage
            boolean actionView = Intent.ACTION_VIEW.equals(action);
            if (actionView || ComposeMessage.ACTION_VIEW_USERID.equals(action)) {
                Uri uri = null;

                if (actionView) {
                    Cursor c = getContentResolver().query(intent.getData(),
                        new String[]{Syncer.DATA_COLUMN_PHONE},
                        null, null, null);
                    if (c.moveToFirst()) {
                        String phone = c.getString(0);
                        String userJID = XMPPUtils.createLocalJID(this,
                            MessageUtils.sha1(phone));
                        uri = Threads.getUri(userJID);
                    }
                    c.close();
                }
                else {
                    uri = intent.getData();
                }

                if (uri != null)
                    openConversation(uri);
            }
        }
    }

    @Override
    public boolean onSearchRequested() {
        ConversationListFragment fragment = getListFragment();

        ListAdapter list = fragment.getListAdapter();
        // no data found
        if (list == null || list.getCount() == 0)
            return false;

        startSearch(null, false, null, false);
        return true;
    }

    @Override
    public void onBackPressed() {
        ComposeMessageFragment f = (ComposeMessageFragment) getSupportFragmentManager()
            .findFragmentById(R.id.fragment_compose_message);
        if (f == null || !f.tryHideEmojiDrawer())
            super.onBackPressed();
    }

    @Override
    public void onResume() {
        super.onResume();

        // set title for offline mode
        setOfflineModeTitle();

        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // mark all messages as old
                MessagesProvider.markAllThreadsAsOld(context);
                // update notification
                MessagingNotification.updateMessagesNotification(context, false);
            }
        }).start();

        if (Authenticator.getDefaultAccount(this) == null) {
            NumberValidation.start(this);
            finish();
        }
        else {
            // hold message center
            MessageCenterService.hold(this, true);
        }

        updateOffline();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // release message center
        MessageCenterService.release(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // contact chooser
        if (requestCode == REQUEST_CONTACT_PICKER) {
            if (resultCode == Activity.RESULT_OK) {
                ArrayList<Uri> uris;
                Uri uri = data.getData();
                if (uri != null) {
                    openConversation(uri);
                }
                else if ((uris = data.getParcelableArrayListExtra("org.kontalk.contacts")) != null) {
                    startGroupChat(uris);
                }
            }
        }
    }

    private void startGroupChat(List<Uri> threads) {
        String selfJid = Authenticator.getSelfJID(this);
        String groupId = StringUtils.randomString(20);
        String groupJid = KontalkGroupCommands.createGroupJid(groupId, selfJid);

        // ensure no duplicates
        Set<String> usersList = new HashSet<>();
        for (Uri uri : threads) {
            String member = uri.getLastPathSegment();
            // exclude ourselves
            if (!member.equalsIgnoreCase(selfJid))
                usersList.add(member);
        }

        if (usersList.size() > 0) {
            askGroupSubject(usersList, groupJid);
        }
    }

    private void askGroupSubject(final Set<String> usersList, final String groupJid) {
        new MaterialDialog.Builder(this)
            .title(R.string.title_group_subject)
            .positiveText(android.R.string.ok)
            .negativeText(android.R.string.cancel)
            .input(null, null, true, new MaterialDialog.InputCallback() {
                @Override
                public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                    String title = !TextUtils.isEmpty(input) ? input.toString() : null;
                    Context ctx = ConversationsActivity.this;

                    String[] users = usersList.toArray(new String[usersList.size()]);
                    long groupThreadId = Conversation.initGroupChat(ctx,
                        groupJid, title, users, "");

                    // store create group command to outbox
                    boolean encrypted = Preferences.getEncryptionEnabled(ctx);
                    String msgId = MessageCenterService.messageId();
                    Uri cmdMsg = KontalkGroupCommands.createGroup(ctx,
                        groupThreadId, groupJid, users, msgId, encrypted);
                    // TODO check for null

                    // send create group command now
                    MessageCenterService.createGroup(ConversationsActivity.this, groupJid, title,
                        users, encrypted, ContentUris.parseId(cmdMsg), msgId);

                    // load the new conversation
                    openConversation(Threads.getUri(groupJid));
                }
            })
            .inputRange(0, MyMessages.Groups.GROUP_SUBJECT_MAX_LENGTH)
            .show();
    }

    public void setOfflineModeTitle() {
        setTitle(MessageCenterService.isOfflineMode(this));
    }

    public void setTitle(boolean offline) {
        setTitle(offline ? R.string.app_name_offline : R.string.app_name);
    }

    public ConversationListFragment getListFragment() {
        return mFragment;
    }

    public boolean isDualPane() {
        return findViewById(R.id.fragment_compose_message) != null;
    }

    @Override
    public void onContactSelected(ContactsListFragment fragment, Contact contact) {
        // open by user hash
        openConversation(Threads.getUri(contact.getJID()));
    }

    /** Called when a contact has been selected from a {@link ContactsListFragment}. */
    @Override
    public void onContactsSelected(ContactsListFragment fragment, List<Contact> contacts) {
        // open by user hash
        // TODO handle multiple contacts
    }

    public void showContactPicker() {
        // TODO one day it will be like this
        // Intent i = new Intent(Intent.ACTION_PICK, Users.CONTENT_URI);
        Intent i = new Intent(this, ContactsListActivity.class);
        startActivityForResult(i, REQUEST_CONTACT_PICKER);
    }

    @Override
    public void setTitle(CharSequence title, CharSequence subtitle) {
        // nothing
    }

    @Override
    public void setUpdatingSubtitle() {
        // nothing
    }

    @Override
    public void loadConversation(long threadId) {
        // TODO for tablets!
    }

    public void openConversation(Conversation conv, int position) {
        if (isDualPane()) {
            mFragment.getListView().setItemChecked(position, true);

            // get the old fragment
            AbstractComposeFragment f = (AbstractComposeFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_compose_message);

            // check if we are replacing the same fragment
            if (f == null || !f.getConversation().getRecipient().equals(conv.getRecipient())) {
                f = ComposeMessageFragment.fromConversation(this, conv);
                // Execute a transaction, replacing any existing fragment
                // with this one inside the frame.
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                ft.replace(R.id.fragment_compose_message, f);
                ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
                ft.commit();
            }
        } else {
            Intent i = ComposeMessage.fromConversation(this, conv);
            startActivity(i);
        }
    }

    private void openConversation(Uri threadUri) {
        if (isDualPane()) {
            // TODO position
            //mFragment.getListView().setItemChecked(position, true);

            // load conversation
            String userId = threadUri.getLastPathSegment();
            Conversation conv = Conversation.loadFromUserId(this, userId);

            // get the old fragment
            AbstractComposeFragment f = (AbstractComposeFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_compose_message);

            // check if we are replacing the same fragment
            if (f == null || conv == null || !f.getConversation().getRecipient().equals(conv.getRecipient())) {
                if (conv == null)
                    f = AbstractComposeFragment.fromUserId(this, userId);
                else
                    f = AbstractComposeFragment.fromConversation(this, conv);

                // Execute a transaction, replacing any existing fragment
                // with this one inside the frame.
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                ft.replace(R.id.fragment_compose_message, f);
                ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
                ft.commitAllowingStateLoss();
            }
        }
        else {
            Intent i = ComposeMessage.fromUserId(this, threadUri.getLastPathSegment());
            if (i != null)
                startActivity(i);
            else
                Toast.makeText(this, R.string.contact_not_registered, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.conversation_list_menu, menu);

        // compose message
        /*
        MenuItem item = menu.findItem(R.id.menu_compose);
        MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
        */

        // search
        mSearchMenu = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchMenu);
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        // LayoutParams.MATCH_PARENT does not work, use a big value instead
        searchView.setMaxWidth(1000000);

        mDeleteAllMenu = menu.findItem(R.id.menu_delete_all);

        // offline mode
        mOfflineMenu = menu.findItem(R.id.menu_offline);

        // trigger manually
        onDatabaseChanged();
        updateOffline();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;

            case R.id.menu_status:
                StatusActivity.start(this);
                return true;

            case R.id.menu_offline:
                final Context ctx = this;
                final boolean currentMode = Preferences.getOfflineMode(ctx);
                if (!currentMode && !Preferences.getOfflineModeUsed(ctx)) {
                    // show offline mode warning
                    new AlertDialogWrapper.Builder(ctx)
                        .setMessage(R.string.message_offline_mode_warning)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                Preferences.setOfflineModeUsed(ctx);
                                switchOfflineMode();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
                else {
                    switchOfflineMode();
                }
                return true;

            case R.id.menu_delete_all:
                deleteAll();
                return true;

            case R.id.menu_mykey:
                launchMyKey();
                return true;

            case R.id.menu_donate:
                launchDonate();
                return true;

            case R.id.menu_settings: {
                PreferencesActivity.start(this);
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    /** Updates various UI elements after a database change. */
    void onDatabaseChanged() {
        boolean visible = mFragment.hasListItems();
        if (mSearchMenu != null) {
            mSearchMenu.setEnabled(visible).setVisible(visible);
        }
        // if it's null it hasn't gone through onCreateOptionsMenu() yet
        if (mSearchMenu != null) {
            mSearchMenu.setEnabled(visible).setVisible(visible);
            mDeleteAllMenu.setEnabled(visible).setVisible(visible);
        }
    }

    /** Updates offline mode menu. */
    private void updateOffline() {
        if (mOfflineMenu != null) {
            boolean offlineMode = Preferences.getOfflineMode(this);
            // set menu
            int icon = (offlineMode) ? R.drawable.ic_menu_online :
                R.drawable.ic_menu_offline;
            int title = (offlineMode) ? R.string.menu_online : R.string.menu_offline;
            mOfflineMenu.setIcon(icon);
            mOfflineMenu.setTitle(title);
            // set window title
            setTitle(offlineMode);
        }
    }

    private void switchOfflineMode() {
        boolean currentMode = Preferences.getOfflineMode(this);
        Preferences.switchOfflineMode(this);
        updateOffline();
        // notify the user about the change
        int text = (currentMode) ? R.string.going_online : R.string.going_offline;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void deleteAll() {
        AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
        builder.setMessage(R.string.confirm_will_delete_all);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Conversation.deleteAll(ConversationsActivity.this);
                MessagingNotification.updateMessagesNotification(getApplicationContext(), false);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.create().show();
    }

    private void launchDonate() {
        Intent i = new Intent(this, AboutActivity.class);
        i.setAction(AboutActivity.ACTION_DONATION);
        startActivity(i);
    }

    private void launchMyKey() {
        Intent i = new Intent(this, MyKeyActivity.class);
        startActivity(i);
    }

}
