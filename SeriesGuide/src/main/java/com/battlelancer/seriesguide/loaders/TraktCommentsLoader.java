package com.battlelancer.seriesguide.loaders;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.traktapi.SgTrakt;
import com.battlelancer.seriesguide.ui.TraktCommentsFragment;
import com.battlelancer.seriesguide.util.MovieTools;
import com.battlelancer.seriesguide.util.ServiceUtils;
import com.battlelancer.seriesguide.util.ShowTools;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.androidutils.GenericSimpleLoader;
import com.uwetrottmann.trakt5.TraktV2;
import com.uwetrottmann.trakt5.entities.Comment;
import com.uwetrottmann.trakt5.enums.Extended;
import java.io.IOException;
import java.util.List;
import retrofit2.Response;
import timber.log.Timber;

/**
 * Loads up comments from trakt for a movie (tvdbId arg is 0), show (episode arg is 0) or episode.
 */
public class TraktCommentsLoader extends GenericSimpleLoader<TraktCommentsLoader.Result> {

    public static class Result {
        public List<Comment> results;
        public int emptyTextResId;

        public Result(List<Comment> results, int emptyTextResId) {
            this.results = results;
            this.emptyTextResId = emptyTextResId;
        }
    }

    private static final int PAGE_SIZE = 25;
    private Bundle mArgs;

    public TraktCommentsLoader(Context context, Bundle args) {
        super(context);
        mArgs = args;
    }

    @Override
    public Result loadInBackground() {
        // movie comments?
        TraktV2 trakt = ServiceUtils.getTrakt(getContext());
        int movieTmdbId = mArgs.getInt(TraktCommentsFragment.InitBundle.MOVIE_TMDB_ID);
        if (movieTmdbId != 0) {
            Integer movieTraktId = MovieTools.lookupTraktId(getContext(), trakt.search(),
                    movieTmdbId);
            if (movieTraktId != null) {
                if (movieTraktId == -1) {
                    return buildResultFailure(R.string.trakt_error_not_exists);
                }
                try {
                    Response<List<Comment>> response = trakt.movies()
                            .comments(String.valueOf(movieTraktId), 1, PAGE_SIZE, Extended.IMAGES)
                            .execute();
                    if (response.isSuccessful()) {
                        return buildResultSuccess(response.body());
                    } else {
                        SgTrakt.trackFailedRequest(getContext(), "get movie comments",
                                response);
                    }
                } catch (IOException e) {
                    SgTrakt.trackFailedRequest(getContext(), "get movie comments", e);
                }
            }
            return buildResultFailureWithOfflineCheck(R.string.trakt_error_general);
        }

        // episode comments?
        int episodeTvdbId = mArgs.getInt(TraktCommentsFragment.InitBundle.EPISODE_TVDB_ID);
        if (episodeTvdbId != 0) {
            // look up episode number, season and show id
            Cursor query = getContext().getContentResolver()
                    .query(SeriesGuideContract.Episodes.buildEpisodeUri(episodeTvdbId),
                            new String[] { SeriesGuideContract.Episodes.SEASON,
                                    SeriesGuideContract.Episodes.NUMBER,
                                    SeriesGuideContract.Shows.REF_SHOW_ID }, null, null, null);
            int season = -1;
            int episode = -1;
            int showTvdbId = -1;
            if (query != null) {
                if (query.moveToFirst()) {
                    season = query.getInt(0);
                    episode = query.getInt(1);
                    showTvdbId = query.getInt(2);
                }
                query.close();
            }

            if (season != -1 && episode != -1 && showTvdbId != -1) {
                // look up show trakt id
                Integer showTraktId = ShowTools.getShowTraktId(getContext(), showTvdbId);
                if (showTraktId == null) {
                    return buildResultFailure(R.string.trakt_error_not_exists);
                }
                try {
                    Response<List<Comment>> response = trakt.episodes()
                            .comments(String.valueOf(showTraktId), season, episode,
                                    1, PAGE_SIZE, Extended.IMAGES).execute();
                    if (response.isSuccessful()) {
                        return buildResultSuccess(response.body());
                    } else {
                        SgTrakt.trackFailedRequest(getContext(), "get episode comments", response);
                    }
                } catch (IOException e) {
                    SgTrakt.trackFailedRequest(getContext(), "get episode comments", e);
                }
                return buildResultFailureWithOfflineCheck(R.string.trakt_error_general);
            } else {
                Timber.e("loadInBackground: could not find episode in database");
                return buildResultFailure(R.string.unknown);
            }
        }

        // show comments!
        int showTvdbId = mArgs.getInt(TraktCommentsFragment.InitBundle.SHOW_TVDB_ID);
        Integer showTraktId = ShowTools.getShowTraktId(getContext(), showTvdbId);
        if (showTraktId == null) {
            return buildResultFailure(R.string.trakt_error_not_exists);
        }
        try {
            Response<List<Comment>> response = trakt.shows()
                    .comments(String.valueOf(showTraktId), 1, PAGE_SIZE, Extended.IMAGES).execute();
            if (response.isSuccessful()) {
                return buildResultSuccess(response.body());
            } else {
                SgTrakt.trackFailedRequest(getContext(), "get show comments", response);
            }
        } catch (IOException e) {
            SgTrakt.trackFailedRequest(getContext(), "get show comments", e);
        }
        return buildResultFailureWithOfflineCheck(R.string.trakt_error_general);
    }

    private static Result buildResultSuccess(List<Comment> results) {
        return new Result(results, R.string.no_shouts);
    }

    private static Result buildResultFailure(int emptyTextResId) {
        return new Result(null, emptyTextResId);
    }

    private Result buildResultFailureWithOfflineCheck(int emptyTextResId) {
        return new Result(null,
                AndroidUtils.isNetworkConnected(getContext()) ? emptyTextResId : R.string.offline);
    }
}
