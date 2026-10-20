package com.quartz.downloader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.*
import android.media.session.*
import android.net.Uri
import android.os.*
import org.json.JSONObject

class PlaybackService : Service() {
    companion object {
        const val CHANNEL="quartz_music"
        @Volatile private var encoded="{\"state\":\"stopped\"}"
        fun state()=JSONObject(encoded)
    }
    private var player: MediaPlayer?=null
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var job: JSONObject?=null
    private var status="stopped"
    private var duration=0
    private var cover: android.graphics.Bitmap?=null
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object:Runnable { override fun run(){ publish();if(status=="playing")handler.postDelayed(this,750) } }
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){pause()}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"Music playback",NotificationManager.IMPORTANCE_LOW))
        audio=getSystemService(AUDIO_SERVICE) as AudioManager
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener{ change -> if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)pause() else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)player?.setVolume(.25f,.25f) else if(change==AudioManager.AUDIOFOCUS_GAIN)player?.setVolume(1f,1f) }.build()
        session=MediaSession(this,"Quartz music").apply{
            setCallback(object:MediaSession.Callback(){
                override fun onPlay(){resume()};override fun onPause(){pause()};override fun onStop(){stopSelf()}
                override fun onSkipToNext(){skip(1)};override fun onSkipToPrevious(){skip(-1)}
                override fun onSeekTo(pos:Long){seek(pos.toInt())}
            });isActive=true
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val action=intent?.action ?: return START_NOT_STICKY
        if(action=="play"){
            val id=intent.getStringExtra("id").orEmpty()
            if(job?.optString("id")==id && status!="failed"){if(status=="playing")pause() else resume()}
            else load(id)
        } else when(action){"toggle"->if(status=="playing")pause() else resume();"next"->skip(1);"previous"->skip(-1);"seek"->seek(intent.getIntExtra("position",0));"stop"->stopSelf()}
        return START_NOT_STICKY
    }
    private fun load(id:String){
        val row=DownloadStore.get(this,id)
        if(row==null || row.optString("state")!="complete" || row.optString("mode")!="audio"){ if(player==null)stopSelf();return }
        handler.removeCallbacks(tick);player?.release();player=null;job=row;status="preparing";duration=0;publish()
        cover= row.optString("artworkPath").takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
        val metadata=MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,row.optString("musicTitle",row.optString("title"))).putString(MediaMetadata.METADATA_KEY_ARTIST,row.optString("musicArtist"))
        cover?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,it) }
        session.setMetadata(metadata.build())
        startForeground(1200,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        try {
            val next=MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setWakeMode(this@PlaybackService,PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@PlaybackService,Uri.parse(row.getString("uri")))
                setOnPreparedListener{this@PlaybackService.duration=it.duration;session.setMetadata(MediaMetadata.Builder(session.controller.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION,it.duration.toLong()).build());resume()}
                setOnCompletionListener{skip(1,true)}
                setOnErrorListener{_,_,_->status="failed";handler.removeCallbacks(tick);audio.abandonAudioFocusRequest(focus);publish("This audio file could not be played.");stopForeground(STOP_FOREGROUND_REMOVE);true}
            }
            player=next;next.prepareAsync()
        }catch(_:Exception){status="failed";publish("This audio file is no longer available.");stopForeground(STOP_FOREGROUND_REMOVE)}
    }
    private fun resume(){
        val p=player ?: return
        if(status=="preparing" && duration==0)return
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){status="paused";publish("Another app is using audio. Try again.");return}
        try {p.start();status="playing";handler.removeCallbacks(tick);handler.post(tick);updateNotification()} catch(_:Exception){status="failed";publish("Could not start playback.")}
    }
    private fun pause(){if(player==null)return;try{if(status=="playing")player?.pause()}catch(_:Exception){};status="paused";handler.removeCallbacks(tick);publish();updateNotification();audio.abandonAudioFocusRequest(focus)}
    private fun seek(value:Int){try{player?.seekTo(value.coerceIn(0,duration));publish()}catch(_:Exception){}}
    private fun skip(direction:Int,ended:Boolean=false){
        val rows=DownloadStore.all(this);val tracks=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.optString("state")=="complete"&&it.optString("mode")=="audio"}
        val index=tracks.indexOfFirst{it.optString("id")==job?.optString("id")}
        if(tracks.isEmpty() || (ended && index==tracks.lastIndex)){pause();return}
        val next=(index+direction+tracks.size)%tracks.size;load(tracks[next].getString("id"))
    }
    private fun publish(message:String=""){
        val row=job
        val pos=try{player?.currentPosition ?: 0}catch(_:Exception){0}
        encoded=JSONObject().put("state",status).put("id",row?.optString("id").orEmpty()).put("position",pos).put("duration",duration).put("message",message)
            .put("title",row?.optString("musicTitle",row.optString("title"))).put("artist",row?.optString("musicArtist")).put("artworkPath",row?.optString("artworkPath")).toString()
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if(status=="playing")PlaybackState.STATE_PLAYING else if(status=="preparing")PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,pos.toLong(),if(status=="playing")1f else 0f).build())
    }
    private fun control(action:String):PendingIntent=PendingIntent.getService(this,0,Intent(this,PlaybackService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification():Notification{
        val open=PendingIntent.getActivity(this,1200,Intent(this,MainActivity::class.java).setData(Uri.parse("quartz://music")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("quartz_tab","music"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val row=job
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_download_notification).setContentTitle(row?.optString("musicTitle",row.optString("title")) ?: "Quartz music")
            .setContentText(row?.optString("musicArtist").orEmpty()).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(status=="playing"||status=="preparing").setColor(0xffb79aff.toInt())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_previous),"Previous",control("previous")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,if(status=="playing")android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),if(status=="playing")"Pause" else "Play",control("toggle")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_next),"Next",control("next")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2))
        cover?.let(builder::setLargeIcon)
        return builder.build()
    }
    private fun updateNotification(){(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1200,notification())}
    override fun onDestroy(){handler.removeCallbacks(tick);player?.release();player=null;audio.abandonAudioFocusRequest(focus);session.release();unregisterReceiver(noisy);encoded="{\"state\":\"stopped\"}";stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
