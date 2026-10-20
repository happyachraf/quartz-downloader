package com.quartz.downloader

import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRankerTest {
    @Test fun studioRecordingBeatsLiveVersionAndCover() {
        val studio = MatchRanker.score("Eyes Without A Face", "Billy Idol", 299.24, "Billy Idol - Eyes Without A Face (Official Audio)", "Billy Idol", 299.0)
        val live = MatchRanker.score("Eyes Without A Face", "Billy Idol", 299.24, "Billy Idol - Eyes Without A Face Live", "Billy Idol", 340.0)
        val cover = MatchRanker.score("Eyes Without A Face", "Billy Idol", 299.24, "Eyes Without A Face - cover of Billy Idol", "Other artist", 299.0)
        assertTrue(studio >= .82)
        assertTrue(live < .82 && cover < .82)
    }
    @Test fun correctTitleWithWrongArtistNeedsReview() {
        assertTrue(MatchRanker.score("Corazon", "ElGrandeToto", 188.37, "Corazon (Official Audio)", "Someone Else", 188.0) < .82)
    }
    @Test fun diacriticsDoNotRejectArtistOrSong() {
        assertTrue(MatchRanker.score("QUÉ LO QUE", "Kaaris", 181.275, "Kaaris - QUE LO QUE feat ElGrandeToto", "Kaaris", 182.0) >= .82)
    }
    @Test fun explicitlyRequestedRemixCanMatch() {
        assertTrue(MatchRanker.score("Song Remix", "Artist", 200.0, "Artist - Song Remix", "Artist", 200.0) >= .82)
        assertTrue(MatchRanker.score("Song", "Artist", 200.0, "Artist - Song Remix", "Artist", 200.0) < .82)
    }
    @Test fun wrongDurationAndMissingDurationDoNotAutoApprove() {
        assertTrue(MatchRanker.score("Song", "Artist", 200.0, "Artist - Song", "Artist", 500.0) < .82)
        assertTrue(MatchRanker.score("Song", "Artist", 200.0, "Artist - Song", "Artist", 0.0) < .82)
    }
    @Test fun genericSongTitleDoesNotSuggestUnrelatedLongTitles() {
        val topic = MatchRanker.score("Birds", "百景", 223.9, "Birds", "Hyakkei - Topic", 224.0)
        val unrelated = MatchRanker.score("Birds", "百景", 223.9, "Chinese Suona Music Hundreds Birds Worshipping The Phoenix Yezhi Guo Live Performance", "Musical Map of China", 219.0)
        assertTrue(topic >= .4 && topic < .82)
        assertTrue(unrelated < .4)
    }
}
