package cz.titulky.tv

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spojení s vlastním Xboxem (Xbox Remote Play / „xHome“) přes WebRTC – jen příjem obrazu,
 * hraje se dál normálním ovladačem u konzole. Postup podle otevřených projektů
 * xbox-xcloud-player (unknownskl, MIT) a XStreaming (Geocld, AGPL-3.0).
 */
object XboxSpojeni {

    data class Konzole(val id: String, val jmeno: String, val stav: String)

    /** kb/s obrazu z Xboxu – titulkům stačí málo, televize pak víc stíhá číst. */
    const val DATOVY_TOK = 3000

    private fun infoZarizeni(): String = JSONObject()
        .put("appInfo", JSONObject().put("env", JSONObject()
            .put("clientAppId", "www.xbox.com").put("clientAppType", "browser")
            .put("clientAppVersion", "29.9.35").put("clientSdkVersion", "10.6.8")
            .put("httpEnvironment", "prod").put("sdkInstallId", "")))
        .put("dev", JSONObject()
            .put("hw", JSONObject().put("make", "Microsoft").put("model", "unknown")
                .put("platformType", "desktop").put("sdktype", "web"))
            .put("os", JSONObject().put("name", "android").put("ver", "22631.2715").put("platform", "desktop"))
            .put("displayInfo", JSONObject()
                .put("dimensions", JSONObject().put("widthInPixels", 1280).put("heightInPixels", 720))
                .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1)))
            .put("browser", JSONObject().put("browserName", "edge").put("browserVersion", "140.0.3485.66")))
        .toString()

    private fun hlavicky(p: XboxUcet.Pristup) = mapOf(
        "Authorization" to "Bearer ${p.gsToken}",
        "X-MS-Device-Info" to infoZarizeni(),
        "X-Gssv-Client" to "XboxComBrowser"
    )

    /** Seznam konzolí na účtu. Volat mimo hlavní vlákno. */
    fun konzole(ctx: Context): List<Konzole> {
        val p = XboxUcet.pristup(ctx)
        val (stav, telo) = XboxUcet.pozadavek("GET", "${p.zakladniAdresa}/v6/servers/home?mr=50", null, hlavicky = hlavicky(p))
        if (stav != 200) error("konzole $stav: ${telo.take(200)}")
        val vysledky = JSONObject(telo).optJSONArray("results") ?: JSONArray()
        return (0 until vysledky.length()).map {
            val k = vysledky.getJSONObject(it)
            Konzole(k.getString("serverId"), k.optString("deviceName", "Xbox"), k.optString("powerState", ""))
        }
    }

    // ------------------------------------------------------------------ jedna relace (obraz z Xboxu)

    class Relace(
        private val ctx: Context,
        private val konzoleId: String,
        /** Stav pro člověka („Připojuji…“, „✔ obraz chodí“, chyba). */
        private val priStavu: (String) -> Unit,
        /** Každý snímek obrazu (zatím jen počítání + náhled). */
        private val priSnimku: ((VideoFrame) -> Unit)? = null,
        /**
         * true = dekódovat obraz do běžné paměti (bez grafického čipu). Telefony Xiaomi z obrazu
         * v grafickém čipu vracejí při převodu jen černou → v telefonu bez něj.
         */
        private val bezGpu: Boolean = false
    ) {
        @Volatile var bezi = false; private set
        @Volatile var snimku = 0L; private set
        @Volatile var sirka = 0; private set
        @Volatile var vyska = 0; private set
        @Volatile private var konec = false

        private var pristup: XboxUcet.Pristup? = null
        private var relaceUrl: String? = null
        private var egl: EglBase? = null
        private var tovarna: PeerConnectionFactory? = null
        private var pc: PeerConnection? = null
        private val kanaly = HashMap<String, DataChannel>()
        private val mistni = ArrayList<IceCandidate>()
        private val sberHotov = CountDownLatch(1)
        private val vlakno = android.os.HandlerThread("xbox").apply { start() }
        private val h = android.os.Handler(vlakno.looper)

        private fun log(s: String) = XboxLog.zapis(ctx, s)

        fun spust() {
            bezi = true
            Thread {
                runCatching { pripoj() }.onFailure {
                    log("[Xbox] CHYBA: ${it.message}")
                    if (!konec) priStavu("✘ " + (it.message ?: "chyba"))
                    zastav()
                }
            }.apply { name = "xbox-start"; isDaemon = true }.start()
        }

        private fun pripoj() {
            priStavu("Přihlašuji…")
            val p = XboxUcet.pristup(ctx).also { pristup = it }
            log("[Xbox] region ${p.zakladniAdresa}")

            // 1) založit relaci
            priStavu("Spouštím vzdálené hraní na Xboxu…")
            val start = JSONObject()
                .put("clientSessionId", "").put("titleId", "").put("systemUpdateGroup", "")
                .put("settings", JSONObject()
                    .put("nanoVersion", "V3;WebrtcTransport.dll")
                    .put("enableTextToSpeech", false).put("highContrast", 0)
                    .put("locale", "en-US").put("useIceConnection", false)
                    .put("timezoneOffsetMinutes", 60).put("sdkType", "web").put("osName", "android"))
                .put("serverId", konzoleId).put("fallbackRegionNames", JSONArray())
            val (s1, t1) = XboxUcet.pozadavek("POST", "${p.zakladniAdresa}/v5/sessions/home/play", start.toString(), hlavicky = hlavicky(p))
            if (s1 !in 200..299) error("start $s1: ${t1.take(200)}")
            val cesta = JSONObject(t1).getString("sessionPath")
            relaceUrl = if (cesta.startsWith("http")) cesta else "${p.zakladniAdresa}/${cesta.trimStart('/')}"
            log("[Xbox] relace $cesta")

            // 2) počkat, až je konzole připravená
            cekejNaStav()
            if (konec) return
            val keepalive = runCatching {
                val (_, tk) = get("configuration")
                JSONObject(tk).optInt("keepAlivePulseInSeconds", 20)
            }.getOrDefault(20).coerceIn(10, 120) // jako v první (fungující) verzi

            // 3) WebRTC
            priStavu("Navazuji spojení s Xboxem…")
            vytvorPeer()
            val nabidka = vytvorNabidku()
            nastavMistni(nabidka)
            val telo = JSONObject()
                .put("messageType", "offer").put("sdp", nabidka.description)
                .put("configuration", JSONObject()
                    .put("chatConfiguration", JSONObject().put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                        .put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                        .put("numChannels", 1).put("sampleFrequencyHz", 24000))
                    .put("chat", verze(1, 1)).put("control", verze(1, 3))
                    .put("input", verze(1, 8)).put("message", verze(1, 1)))
            val (s2, _) = post("sdp", telo.toString())
            if (s2 !in 200..299) error("sdp $s2")
            val odpoved = JSONObject(cekejNaVymenu("sdp"))
            if (odpoved.optString("status", "success") != "success") error("sdp odmítnuto: ${odpoved.optString("status")}")
            nastavVzdalenou(odpoved.getString("sdp"))
            log("[Xbox] SDP vyměněno")

            // 4) ICE
            sberHotov.await(4, TimeUnit.SECONDS)
            val moje = JSONArray()
            synchronized(mistni) {
                for (c in mistni) moje.put(JSONObject().put("candidate", c.sdp).put("sdpMLineIndex", c.sdpMLineIndex).put("sdpMid", c.sdpMid))
            }
            val (s3, _) = post("ice", JSONObject().put("messageType", "iceCandidate").put("candidate", moje).toString())
            if (s3 !in 200..299) error("ice $s3")
            val jeho = JSONArray(cekejNaVymenu("ice"))
            var pridano = 0
            for (i in 0 until jeho.length()) {
                val c = jeho.getJSONObject(i)
                val text = c.optString("candidate")
                if (text == "a=end-of-candidates" || text.isEmpty()) continue
                val mid = c.optString("sdpMid", "0")
                val idx = c.optString("sdpMLineIndex", "0").toDoubleOrNull()?.toInt() ?: 0
                for (t in teredo(text) + text) {
                    pc?.addIceCandidate(IceCandidate(mid, idx, t)); pridano++
                }
            }
            log("[Xbox] ICE: moje ${moje.length()}, Xbox $pridano")

            // udržovat relaci naživu
            val udrzuj = object : Runnable {
                override fun run() {
                    if (konec) return
                    val v = runCatching { post("keepalive", "{}").first }.getOrElse { -1 }
                    if (v !in 200..299) log("[Xbox] keepalive: $v")
                    h.postDelayed(this, keepalive * 1000L)
                }
            }
            h.postDelayed(udrzuj, keepalive * 1000L)
            zacatek = System.currentTimeMillis()
            log("[Xbox] keepalive každých $keepalive s")
        }

        @Volatile private var zacatek = 0L

        private fun verze(min: Int, max: Int) = JSONObject().put("minVersion", min).put("maxVersion", max)

        private fun cekejNaStav() {
            val doKdy = System.currentTimeMillis() + 90_000
            var pripojeno = false
            while (!konec && System.currentTimeMillis() < doKdy) {
                val (s, t) = get("state")
                if (s !in 200..299) error("stav $s")
                val j = JSONObject(t)
                when (val stav = j.optString("state")) {
                    "Provisioned" -> { log("[Xbox] konzole připravena"); return }
                    "ReadyToConnect" -> if (!pripojeno) {
                        pripojeno = true
                        log("[Xbox] ReadyToConnect → posílám přihlášení")
                        val (sc, _) = post("connect", JSONObject().put("userToken", XboxUcet.tokenPropojeni(ctx)).toString())
                        if (sc !in 200..299) error("connect $sc")
                    }
                    "Failed" -> {
                        val zprava = j.optJSONObject("errorDetails")?.optString("message") ?: ""
                        error(prelozChybu(zprava))
                    }
                    else -> log("[Xbox] stav: $stav")
                }
                Thread.sleep(1000)
            }
            if (!konec) error("Xbox neodpověděl včas")
        }

        private fun prelozChybu(z: String) = when {
            "WaitingForServerToRegister" in z || "register" in z.lowercase() ->
                "Xbox není připravený – zapni na Xboxu „Vzdálené funkce“ a režim napájení „Spánek“"
            else -> "Xbox odmítl spojení: $z"
        }

        private fun cekejNaVymenu(co: String): String {
            val doKdy = System.currentTimeMillis() + 30_000
            while (!konec && System.currentTimeMillis() < doKdy) {
                val (s, t) = get(co)
                if (s == 200 && t.isNotBlank()) {
                    val j = JSONObject(t)
                    val v = j.optString("exchangeResponse")
                    if (v.isNotEmpty()) return v
                    j.optJSONObject("errorDetails")?.let { error("$co: ${it.optString("message")}") }
                }
                Thread.sleep(700)
            }
            error("$co: Xbox neodpověděl")
        }

        private fun get(co: String) = XboxUcet.pozadavek("GET", "$relaceUrl/$co", null, hlavicky = hlavicky(pristup!!))
        private fun post(co: String, telo: String) = XboxUcet.pozadavek("POST", "$relaceUrl/$co", telo, hlavicky = hlavicky(pristup!!))

        // ------------------------------------------------------------------ WebRTC

        private fun vytvorPeer() {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
            )
            val e = EglBase.create().also { egl = it }
            val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(if (bezGpu) null else e.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
                .setAudioDeviceModule(adm)
                // jen domácí síť: ne mobilní data ani VPN (telefon jinak nabízel 15 cest i přes mobilní
                // data – televize jen 3 a spojení jí drželo; telefon Xbox po minutě vyhazoval)
                .setOptions(PeerConnectionFactory.Options().apply {
                    // hodnoty z WebRTC: 4 = mobilní data, 8 = VPN, 16 = loopback
                    networkIgnoreMask = 4 or 8 or 16
                })
                .createPeerConnectionFactory()
            tovarna = f
            val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST
            }
            val p = f.createPeerConnection(cfg, pozorovatel) ?: error("WebRTC se nespustilo")
            pc = p
            // kanály jako webový přehrávač Xboxu
            otevriKanal("chat", "chatV1")
            otevriKanal("control", "controlV1")
            otevriKanal("input", "1.0")
            otevriKanal("message", "messageV1")
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }

        private fun otevriKanal(jmeno: String, protokol: String) {
            val init = DataChannel.Init().apply { ordered = true; protocol = protokol }
            val k = pc!!.createDataChannel(jmeno, init) ?: return
            kanaly[jmeno] = k
            k.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    if (k.state() == DataChannel.State.OPEN) h.post { kanalOtevren(jmeno) }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val b = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    h.post { zprava(jmeno, b) }
                }
            })
        }

        private fun posli(kanal: String, text: String) = posli(kanal, text.toByteArray())
        private fun posli(kanal: String, data: ByteArray) {
            val k = kanaly[kanal] ?: return
            if (k.state() != DataChannel.State.OPEN) return
            runCatching { k.send(DataChannel.Buffer(ByteBuffer.wrap(data), true)) }
        }

        private fun kanalOtevren(jmeno: String) {
            log("[Xbox] kanál $jmeno otevřen")
            if (jmeno == "message") posli("message", JSONObject()
                .put("type", "Handshake").put("version", "messageV1")
                .put("id", UUID.randomUUID().toString()).put("cv", "0").toString())
        }

        private fun zprava(kanal: String, data: ByteArray) {
            if (kanal != "message") return
            val j = runCatching { JSONObject(String(data)) }.getOrNull() ?: return
            when (j.optString("type")) {
                "HandshakeAck" -> {
                    log("[Xbox] handshake OK")
                    // control: autorizace + ovladač přidán a hned odebrán (jako webový přehrávač)
                    posli("control", JSONObject().put("message", "authorizationRequest")
                        .put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E").toString())
                    // ovladač „přidat a hned odebrat“ – přesně jako webový přehrávač xbox-xcloud-player
                    // a jako naše první verze, se kterou spojení vydrželo i 12 minut. Bez toho (nebo
                    // s „odebrat a přidat“) Xbox po minutě vyhodil („KickForNoClientConnection“).
                    posli("control", JSONObject().put("message", "gamepadChanged").put("gamepadIndex", 0).put("wasAdded", true).toString())
                    posli("control", JSONObject().put("message", "gamepadChanged").put("gamepadIndex", 0).put("wasAdded", false).toString())
                    // input: metadata klienta (15 bajtů)
                    val m = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
                    m.putShort(8.toShort()); m.putInt(0); m.putDouble(System.nanoTime() / 1e6); m.put(1.toByte())
                    posli("input", m.array())
                    posliNastaveni()
                    // klíčový snímek jen na začátku – opakované vyžadování by zbytečně zatěžovalo Xbox
                    // (na Xboxu One S se pak mohl zadrhávat obraz i na televizi)
                    posli("control", JSONObject().put("message", "videoKeyframeRequested").put("ifrRequested", true).toString())
                    // obraz se zastavil (ztracené pakety) → požádat o nový snímek, ale jen tehdy
                    // (pravidelné vyžadování zatěžovalo Xbox, bez něj se zas obraz po výpadku nevzpamatoval)
                    val hlidac = object : Runnable {
                        override fun run() {
                            if (konec) return
                            val ted = System.currentTimeMillis()
                            val stoji = posledniSnimek > 0 && ted - posledniSnimek > 1200 && ted - posledniZadost > 2000
                            // každých 15 s se ozvat i tak – jinak Xbox po minutě spojení ukončí
                            // („KickForNoClientConnection“); dřív to dělalo vyžadování snímku každých 5 s
                            val ozvatSe = ted - posledniZadost > 5_000 // jako XStreaming
                            if (stoji || ozvatSe) {
                                posledniZadost = ted
                                if (stoji) log("[Xbox] obraz stojí ${(ted - posledniSnimek)} ms → žádám nový snímek")
                                posli("control", JSONObject().put("message", "videoKeyframeRequested").put("ifrRequested", true).toString())
                            }
                            h.postDelayed(this, 500)
                        }
                    }
                    h.postDelayed(hlidac, 2000)
                }
                "TransactionStart", "Message" -> {
                    val cil = j.optString("target")
                    // pro hledání chyb: co Xbox posílá (bez opakovaných titleinfo)
                    if (!cil.endsWith("titleinfo")) log("[Xbox] zpráva: $cil ${j.optString("content").take(120)}")
                    if (cil.endsWith("serverInitiatedDisconnect")) {
                        val minut = if (zacatek > 0) (System.currentTimeMillis() - zacatek) / 60000.0 else 0.0
                        log("[Xbox] konzole ukončila spojení po ${"%.1f".format(minut)} min")
                        priStavu("Xbox ukončil vzdálené hraní")
                        zastav()
                    }
                }
            }
        }

        private fun posliNastaveni() {
            fun zpr(cil: String, obsah: JSONObject) = posli("message", JSONObject()
                .put("type", "Message").put("content", obsah.toString())
                .put("id", UUID.randomUUID().toString()).put("target", cil).put("cv", "").toString())
            zpr("/streaming/systemUi/configuration", JSONObject().put("version", JSONArray(listOf(0, 2, 0))).put("systemUis", JSONArray()))
            zpr("/streaming/properties/clientappinstallidchanged", JSONObject().put("clientAppInstallId", UUID.randomUUID().toString()))
            zpr("/streaming/characteristics/orientationchanged", JSONObject().put("orientation", 0))
            zpr("/streaming/characteristics/touchinputenabledchanged", JSONObject().put("touchInputEnabled", false))
            zpr("/streaming/characteristics/clientdevicecapabilities", JSONObject())
            zpr("/streaming/characteristics/dimensionschanged", JSONObject()
                .put("horizontal", 1280).put("vertical", 720).put("preferredWidth", 1280).put("preferredHeight", 720)
                .put("safeAreaLeft", 0).put("safeAreaTop", 0).put("safeAreaRight", 1280).put("safeAreaBottom", 720)
                .put("supportsCustomResolution", true))
        }

        @Volatile private var posledniSnimek = 0L
        @Volatile private var posledniZadost = 0L

        private val sink = VideoSink { f ->
            snimku++
            posledniSnimek = System.currentTimeMillis()
            sirka = f.rotatedWidth; vyska = f.rotatedHeight
            if (snimku == 1L) {
                log("[Xbox] PRVNÍ SNÍMEK ${sirka}x$vyska (${f.buffer.javaClass.simpleName}${if (bezGpu) ", bez GPU" else ""})")
                priStavu("✔ Obraz z Xboxu chodí")
            }
            runCatching { priSnimku?.invoke(f) }
        }

        private val pozorovatel = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                log("[Xbox] ICE: $s")
                if (s == PeerConnection.IceConnectionState.FAILED) { priStavu("✘ Spojení s Xboxem se nepodařilo (síť)"); zastav() }
                if (s == PeerConnection.IceConnectionState.DISCONNECTED || s == PeerConnection.IceConnectionState.CLOSED) {
                    if (!konec) priStavu("Spojení s Xboxem přerušeno")
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) sberHotov.countDown()
            }
            override fun onIceCandidate(c: IceCandidate) { synchronized(mistni) { mistni.add(c) } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(k: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {
                when (val t = r?.track()) {
                    is VideoTrack -> { log("[Xbox] video stopa"); t.addSink(sink) }
                    // zvuk hraje televize sama – v telefonu ztlumit
                    is AudioTrack -> t.setEnabled(false)
                }
            }
        }

        private fun vytvorNabidku(): SessionDescription {
            var vysledek: SessionDescription? = null
            var chyba: String? = null
            val l = CountDownLatch(1)
            val omezeni = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            pc!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) { vysledek = d; l.countDown() }
                override fun onCreateFailure(e: String?) { chyba = e; l.countDown() }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, omezeni)
            l.await(10, TimeUnit.SECONDS)
            val d = vysledek ?: error("offer: $chyba")
            return SessionDescription(d.type, h264Napred(d.description))
        }

        private fun nastavMistni(d: SessionDescription) = nastav(d, true)
        private fun nastavVzdalenou(sdp: String) = nastav(SessionDescription(SessionDescription.Type.ANSWER, sdp), false)

        private fun nastav(d: SessionDescription, mistni: Boolean) {
            var chyba: String? = null
            val l = CountDownLatch(1)
            val o = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetSuccess() { l.countDown() }
                override fun onSetFailure(e: String?) { chyba = e ?: "?"; l.countDown() }
            }
            if (mistni) pc!!.setLocalDescription(o, d) else pc!!.setRemoteDescription(o, d)
            l.await(10, TimeUnit.SECONDS)
            chyba?.let { error((if (mistni) "local" else "remote") + " SDP: $it") }
        }

        /** Xbox posílá H.264 – dát ho v nabídce na první místo (high → baseline). */
        private fun h264Napred(sdp: String): String {
            val radky = sdp.split("\r\n").toMutableList()
            val mIdx = radky.indexOfFirst { it.startsWith("m=video") }
            if (mIdx < 0) return sdp
            val casti = radky[mIdx].split(" ")
            if (casti.size <= 3) return sdp
            val typy = casti.drop(3)
            fun poradi(pt: String): Int {
                val kodek = radky.firstOrNull { it.startsWith("a=rtpmap:$pt ") } ?: return 9
                if (!kodek.contains("H264", true)) return 5
                val fmtp = radky.firstOrNull { it.startsWith("a=fmtp:$pt ") } ?: ""
                return when {
                    "profile-level-id=4d" in fmtp -> 0
                    "profile-level-id=42e" in fmtp -> 1
                    "profile-level-id=420" in fmtp -> 2
                    else -> 3
                }
            }
            val serazene = typy.sortedBy { poradi(it) }
            radky[mIdx] = (casti.take(3) + serazene).joinToString(" ")
            // menší datový tok obrazu (stačí čitelné titulky) → televize méně dekóduje a víc stíhá číst
            var vloz = mIdx + 1
            while (vloz < radky.size && (radky[vloz].startsWith("i=") || radky[vloz].startsWith("c="))) vloz++
            if (vloz < radky.size && radky[vloz].startsWith("b=")) radky[vloz] = "b=AS:$DATOVY_TOK"
            else radky.add(vloz, "b=AS:$DATOVY_TOK")
            return radky.joinToString("\r\n")
        }

        /** Xbox často posílá adresu Teredo (IPv6) – rozbalit na IPv4 kandidáty jako XStreaming. */
        private fun teredo(kandidat: String): List<String> {
            val t = kandidat.trim().split(Regex("\\s+"))
            if (t.size < 6) return emptyList()
            val ip = t[4]
            if (!ip.lowercase().startsWith("2001:0:") && !ip.lowercase().startsWith("2001:0000:")) return emptyList()
            return runCatching {
                val b = java.net.InetAddress.getByName(ip).address
                if (b.size != 16) return emptyList()
                val server = "${b[4].toInt() and 255}.${b[5].toInt() and 255}.${b[6].toInt() and 255}.${b[7].toInt() and 255}"
                val klient = "${b[12].toInt().inv() and 255}.${b[13].toInt().inv() and 255}.${b[14].toInt().inv() and 255}.${b[15].toInt().inv() and 255}"
                val portKlient = (((b[10].toInt() and 255) shl 8) or (b[11].toInt() and 255)).inv() and 0xFFFF
                val port = t[5].toIntOrNull() ?: 9002
                listOf(
                    "a=candidate:10 1 UDP 100 $klient $portKlient typ host ",
                    "a=candidate:11 1 UDP 100 $klient $port typ host ",
                    "a=candidate:12 1 UDP 100 $server $port typ host "
                )
            }.getOrDefault(emptyList())
        }

        /** Ukončit: relaci na Xboxu i WebRTC. Dá se volat víckrát. */
        fun zastav() {
            if (konec) return
            konec = true
            bezi = false
            val url = relaceUrl
            val p = pristup
            Thread {
                if (url != null && p != null) runCatching { XboxUcet.pozadavek("DELETE", url, null, hlavicky = hlavicky(p)) }
                // pořadí podle WebRTC: nejdřív zavřít spojení, pak uvolnit kanály, spojení a továrnu
                // (opačné pořadí může v nativní knihovně shodit aplikaci)
                runCatching { pc?.close() }
                runCatching { kanaly.values.forEach { it.unregisterObserver(); it.dispose() } }
                kanaly.clear()
                runCatching { pc?.dispose() }
                pc = null
                runCatching { tovarna?.dispose() }
                runCatching { egl?.release() }
                runCatching { vlakno.quitSafely() }
                log("[Xbox] ukončeno")
            }.apply { isDaemon = true }.start()
        }

        /** Náhled snímku (šedý, zmenšený) – jen pro test. Volat v priSnimku. */
        fun nahled(f: VideoFrame, zmenseni: Int = 4): Bitmap? = runCatching {
            val i = f.buffer.toI420() ?: return null
            try {
                val w = i.width / zmenseni; val hh = i.height / zmenseni
                val y = i.dataY; val krok = i.strideY
                val px = IntArray(w * hh)
                for (r in 0 until hh) for (c in 0 until w) {
                    val v = y.get(r * zmenseni * krok + c * zmenseni).toInt() and 255
                    px[r * w + c] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888)
            } finally { i.release() }
        }.getOrNull()
    }
}
