package com.dyadav.chirpntweet.activity;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;

import com.dyadav.chirpntweet.R;
import com.dyadav.chirpntweet.adapter.TweetAdapter;
import com.dyadav.chirpntweet.application.TwitterApplication;
import com.dyadav.chirpntweet.data.TwitterDb;
import com.dyadav.chirpntweet.databinding.ActivityTimelineBinding;
import com.dyadav.chirpntweet.fragments.ComposeDialog;
import com.dyadav.chirpntweet.modal.Tweet;
import com.dyadav.chirpntweet.modal.Tweet_Table;
import com.dyadav.chirpntweet.modal.User;
import com.dyadav.chirpntweet.rest.TwitterClient;
import com.dyadav.chirpntweet.utils.EndlessRecyclerViewScrollListener;
import com.dyadav.chirpntweet.utils.ItemClickSupport;
import com.dyadav.chirpntweet.utils.NetworkUtility;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.raizlabs.android.dbflow.config.FlowManager;
import com.raizlabs.android.dbflow.sql.language.SQLite;
import com.raizlabs.android.dbflow.structure.database.DatabaseWrapper;
import com.raizlabs.android.dbflow.structure.database.transaction.ProcessModelTransaction;
import com.raizlabs.android.dbflow.structure.database.transaction.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cz.msebera.android.httpclient.Header;

public class TimelineActivity extends AppCompatActivity {

    private TwitterClient client;
    ArrayList<Tweet> mTweetList;
    private TweetAdapter mAdapter;
    EndlessRecyclerViewScrollListener scrollListener;
    LinearLayoutManager mLayoutManager;
    private ActivityTimelineBinding binding;
    private User user = null;
    private boolean offlineData = false;

    private String TAG = "TimelineActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_timeline);
        client = TwitterApplication.getRestClient();
        //Setting toolbar
        setSupportActionBar(binding.toolbar);

        // Display icon in the toolbar
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setLogo(R.drawable.twitter_logo);
        getSupportActionBar().setDisplayUseLogoEnabled(true);


        mTweetList = new ArrayList<>();
        mAdapter = new TweetAdapter(this, mTweetList);
        binding.rView.setAdapter(mAdapter);
        binding.rView.setItemAnimator(new DefaultItemAnimator());

        //Recylerview decorater
        RecyclerView.ItemDecoration itemDecoration =
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        binding.rView.addItemDecoration(itemDecoration);

        mLayoutManager = new LinearLayoutManager(this);
        binding.rView.setLayoutManager(mLayoutManager);

        //Endless pagination
        scrollListener = new EndlessRecyclerViewScrollListener(mLayoutManager) {
            @Override
            public void onLoadMore(int page, int totalItemsCount, RecyclerView view) {
                //Handle fetching in a thread with delay to avoid error "API Limit reached" = 429
                Handler handler = new Handler();

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        populateTimeline(false, getMaxId());
                    }
                }, 1000);
            }
        };
        binding.rView.addOnScrollListener(scrollListener);

        //Swipe to refresh
        binding.swipeContainer.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                //Check internet
                if(!NetworkUtility.isOnline()) {
                    Snackbar.make(binding.cLayout, R.string.connection_error, Snackbar.LENGTH_LONG).show();
                    binding.swipeContainer.setRefreshing(false);
                    return;
                }
                //Fetch first page
                populateTimeline(true, 0);
            }
        });

        //Click on tweet for detailed activity
        ItemClickSupport.addTo(binding.rView).setOnItemClickListener(new ItemClickSupport.OnItemClickListener() {
            @Override
            public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                Intent intent = new Intent(TimelineActivity.this, DetailedActivity.class);
                Tweet tweet = mTweetList.get(position);
                intent.putExtra("tweet", tweet);
                startActivityForResult(intent, 1);
            }
        });

        // Attach FAB listener
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ComposeDialog fDialog = new ComposeDialog();
                if (user != null) {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable("userinfo", user);
                    //Dialog listener
                    fDialog.setFinishDialogListener(new ComposeDialog.ComposeTweetListener(){
                        @Override
                        public void onFinishDialog(Tweet tweet) {
                            if (tweet != null) {
                                mTweetList.add(0,tweet);
                                mAdapter.notifyItemInserted(0);
                                binding.rView.scrollToPosition(0);
                            }
                        }
                    });
                    fDialog.setArguments(bundle);
                }
                fDialog.show(TimelineActivity.this.getSupportFragmentManager(),"");
            }
        });

        //Fetch logged in users info
        fetchUserInfo();

        //Fetch first page
        populateTimeline(true, 0);
    }

    private void fetchUserInfo() {
        client.getAccountInfo(new JsonHttpResponseHandler(){
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                Log.d(TAG, response.toString());
                user = User.fromJson(response);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject object) {
                Snackbar.make(binding.cLayout, R.string.user_info_error, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private long getMaxId(){
        return mTweetList.get(mTweetList.size()-1).getUid();
    }

    private void populateTimeline(final boolean fRequest, long id) {

        binding.progressBar.setVisibility(View.VISIBLE);

        if(!NetworkUtility.isOnline()) {
            Snackbar.make(binding.cLayout, R.string.connection_error, Snackbar.LENGTH_LONG).show();
            binding.progressBar.setVisibility(View.GONE);
            //Show offline data
            fetchOfflineTweets();
            return;
        }

        client.getHomeTimeline(fRequest, id, new JsonHttpResponseHandler(){
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONArray response) {
                if(fRequest)
                    mTweetList.clear();

                ArrayList<Tweet> newTweet = Tweet.fromJSONArray(response);
                mTweetList.addAll(newTweet);
                addToDb(newTweet);
                mAdapter.notifyDataSetChanged();
                binding.swipeContainer.setRefreshing(false);
                binding.progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject object) {
                Snackbar.make(binding.cLayout, R.string.error_fetch, Snackbar.LENGTH_LONG).show();
                //Show offline data
                fetchOfflineTweets();
                binding.swipeContainer.setRefreshing(false);
                binding.progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void fetchOfflineTweets() {
        if (!offlineData) {
            List<Tweet> tweetsFromDb = SQLite.select().from(Tweet.class).orderBy(Tweet_Table.uid, false).queryList();
            mTweetList.addAll(tweetsFromDb);
            offlineData = true;
            mAdapter.notifyDataSetChanged();
        }
    }

    void addToDb(ArrayList<Tweet> newTweet){
        FlowManager.getDatabase(TwitterDb.class)
                .beginTransactionAsync(new ProcessModelTransaction.Builder<>(
                        new ProcessModelTransaction.ProcessModel<Tweet>() {
                            @Override
                            public void processModel(Tweet tweet, DatabaseWrapper wrapper) {
                                tweet.save();
                            }
                        }).addAll(newTweet).build())
                .error(new Transaction.Error() {
                    @Override
                    public void onError(Transaction transaction, Throwable error) {

                    }
                })
                .success(new Transaction.Success() {
                    @Override
                    public void onSuccess(Transaction transaction) {

                    }
                }).build().execute();
    }
}
