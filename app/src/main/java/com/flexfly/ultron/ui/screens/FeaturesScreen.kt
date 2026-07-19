// RECOVERY NOTE: layout approximated from shredded Compose
package com.flexfly.ultron.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexfly.ultron.model.FeatureItem

// Features palette recovered verbatim from Featuresscreen.kt (ARGB longs).
private val BgDeep = Color(0xFF06060A)
private val HeroPurple = Color(0xFFC4B5FD)
private val HeroBlue = Color(0xFF60A5FA)
private val HeroGreen = Color(0xFF34D399)
private val HeroOrange = Color(0xFFFB923C)
private val HeroPink = Color(0xFFF472B6)
private val HeroYellow = Color(0xFFFCD34D)
private val HeroCyan = Color(0xFF22D3EE)
private val W100 = Color(0xFFFFFFFF)
private val W35 = Color(0x59FFFFFF)
private val W25 = Color(0x40FFFFFF)
private val W07 = Color(0x12FFFFFF)
private val W03 = Color(0x08FFFFFF)

/** Glass-card surface recovered from `glassCard(radius)` — translucent fill + hairline border. */
private fun Modifier.glassCard(radius: Int = 22): Modifier = this
    .background(W03, RoundedCornerShape(radius.dp))
    .border(0.5.dp, W07, RoundedCornerShape(radius.dp))

/**
 * The full feature catalog — recovered EXACTLY from Featuresscreen.kt (13 items).
 * Order, emoji, bilingual copy, badges, examples, and accent colors are verbatim.
 */
private val FEATURES: List<FeatureItem> = listOf(
    FeatureItem(
        emoji = "🎙️",
        titleEn = "Voice Assistant",
        titleHi = "वॉयस असिस्टेंट",
        badge = "Core",
        descEn = "Speak to Ultron naturally. It performs commands and speaks in your selected assistant language.",
        descHi = "Ultron से प्राकृतिक रूप से बातचीत करें। वह आपके निर्देशों का पालन करेगा और चुनी हुई भाषा में जवाब देगा।",
        examples = listOf("\"Bolo Ultron\"", "\"Hello Ultron\"", "\"Ultron suno\""),
        accentColor = HeroPurple,
    ),
    FeatureItem(
        emoji = "🔦",
        titleEn = "Flashlight Control",
        titleHi = "फ़्लैशलाइट नियंत्रण",
        badge = "Utility",
        descEn = "Turn the phone's flashlight on, off, or toggle it instantly using simple voice commands.",
        descHi = "वॉयस कमांड के ज़रिए फ़ोन की फ़्लैशलाइट को तुरंत चालू, बंद या टॉगल करें।",
        examples = listOf("\"torch jala do\"", "\"torch band karo\"", "\"flashlight toggle\""),
        accentColor = HeroBlue,
    ),
    FeatureItem(
        emoji = "📷",
        titleEn = "Camera & Selfie",
        titleHi = "कैमरा और सेल्फ़ी",
        badge = "Media",
        descEn = "Capture photos or selfies instantly, record videos, or start camera recordings with countdown timers.",
        descHi = "तुरंत फ़ोटो या सेल्फ़ी लें, वीडियो रिकॉर्ड करें या टाइमर के साथ कैमरा रिकॉर्डिंग शुरू करें।",
        examples = listOf("\"selfie khicho\"", "\"photo lo\"", "\"5 second ka video banao\""),
        accentColor = HeroPink,
    ),
    FeatureItem(
        emoji = "👁️",
        titleEn = "Scene Analysis",
        titleHi = "आस-पास का विश्लेषण",
        badge = "Vision",
        descEn = "Use the camera to describe the scene in front of you, identify objects, or answer specific questions about what is seen.",
        descHi = "कैमरे के ज़रिए अपने सामने की चीज़ों को समझें, वस्तुओं को पहचानें या सामने दिख रहे दृश्य पर प्रश्न पूछें।",
        examples = listOf(
            "\"mere samne kya hai dekh kar batao\"",
            "\"cup mein kya likha hai dekh kar batao\"",
        ),
        accentColor = HeroGreen,
    ),
    FeatureItem(
        emoji = "📱",
        titleEn = "Screen Analysis",
        titleHi = "स्क्रीन का विश्लेषण",
        badge = "Vision",
        descEn = "Let Ultron analyze what is currently open on your screen (or a photo in your gallery) and answer details about it.",
        descHi = "Ultron से आपकी स्क्रीन पर खुली हुई चीज़ों या गैलरी की फ़ोटो का विश्लेषण कराएं और सवाल पूछें।",
        examples = listOf(
            "\"screen analyze karo\"",
            "\"gallery wali photo dekh kar batao iska price kya hai\"",
        ),
        accentColor = HeroCyan,
    ),
    FeatureItem(
        emoji = "⚡",
        titleEn = "Smart Screen Clicks",
        titleHi = "स्मार्ट स्क्रीन क्लिक",
        badge = "Navigation",
        descEn = "Command Ultron to tap on specific buttons, open apps, or click any visible text or item on the screen.",
        descHi = "स्क्रीन पर दिखने वाले विशिष्ट बटनों, ऐप्स या किसी भी टेक्स्ट पर क्लिक/टैप करने का निर्देश दें।",
        examples = listOf("\"Settings click karo\"", "\"YouTube tap karo\"", "\"Ravi click karo\""),
        accentColor = HeroOrange,
    ),
    FeatureItem(
        emoji = "📞",
        titleEn = "Incoming Call Control",
        titleHi = "कॉल नियंत्रण",
        badge = "Calls",
        descEn = "Ultron announces incoming calls and asks if you want to answer ('Haan') or reject ('Nahi') them.",
        descHi = "इनकमिंग कॉल आने पर Ultron आपको सूचित करेगा और पूछेगा कि कॉल उठानी है या काटनी है।",
        examples = listOf("\"call uthao\"", "\"pickup\"", "\"call reject karo\""),
        accentColor = HeroGreen,
    ),
    FeatureItem(
        emoji = "💬",
        titleEn = "WhatsApp Messaging",
        titleHi = "व्हाट्सएप संदेश और कॉल",
        badge = "Social",
        descEn = "Send WhatsApp messages or initiate voice and video calls to contacts hands-free.",
        descHi = "बिना फ़ोन छुए किसी भी संपर्क को व्हाट्सएप संदेश भेजें या वॉयस/वीडियो कॉल शुरू करें।",
        examples = listOf(
            "\"Ravi ko WhatsApp message bhejo [Message]\"",
            "\"Ravi ko WhatsApp call lagao\"",
        ),
        accentColor = HeroCyan,
    ),
    FeatureItem(
        emoji = "✉️",
        titleEn = "Calls & SMS",
        titleHi = "फ़ोन कॉल और एसएमएस",
        badge = "Calls",
        descEn = "Make regular phone calls or send SMS messages to your contacts, prioritizing priority/favorite contacts.",
        descHi = "अपने संपर्कों को सामान्य फ़ोन कॉल करें या एसएमएस भेजें, पसंदीदा संपर्कों को प्राथमिकता दी जाएगी।",
        examples = listOf(
            "\"Ravi ko call karo\"",
            "\"Ravi ko normal message bhejo [Message]\"",
        ),
        accentColor = HeroBlue,
    ),
    FeatureItem(
        emoji = "⚙️",
        titleEn = "System Settings",
        titleHi = "सिस्टम सेटिंग्स",
        badge = "Utility",
        descEn = "Change device volume levels, screen brightness, or toggle Wi-Fi, Bluetooth, Mobile Data, and Airplane mode.",
        descHi = "फ़ोन का वॉल्यूम, ब्राइटनेस नियंत्रित करें या वाई-फ़ाई, ब्लूटूथ, मोबाइल डेटा और एरोप्लेन मोड बदलें।",
        examples = listOf("\"volume up karo\"", "\"brightness badhao\"", "\"Wi-Fi chalu karo\""),
        accentColor = HeroPurple,
    ),
    FeatureItem(
        emoji = "🛡️",
        titleEn = "Anti-Theft Guard",
        titleHi = "एंटी-थेफ्ट गार्ड",
        badge = "Security",
        descEn = "Activate Touch Guard. If someone touches your phone, an alarm plays and the front camera captures their photo.",
        descHi = "टच गार्ड सक्रिय करें। अगर कोई आपका फ़ोन छुएगा, तो अलार्म बजेगा और सेल्फ़ी कैमरे से उसकी फ़ोटो ले ली जाएगी।",
        examples = listOf(
            "\"touch guard active karo\"",
            "\"alarm band karo\"",
            "\"kisne chhua uski photo dikhao\"",
        ),
        accentColor = HeroOrange,
    ),
    FeatureItem(
        emoji = "💾",
        titleEn = "Persistent Memory",
        titleHi = "परसिस्टेंट मेमोरी",
        badge = "Core",
        descEn = "Ultron remembers key facts about you (e.g., favorite food, name) so it behaves more personally and remembers context.",
        descHi = "Ultron को अपने बारे में महत्वपूर्ण बातें याद रखने को कहें ताकि वह आपके अनुसार अनुकूलित अनुभव दे सके।",
        examples = listOf(
            "\"yaad rakhna ki mera favorite color blue hai\"",
            "\"meri memory clear karo\"",
        ),
        accentColor = HeroYellow,
    ),
    FeatureItem(
        emoji = "ℹ️",
        titleEn = "System Info & Weather",
        titleHi = "सिस्टम जानकारी और मौसम",
        badge = "Utility",
        descEn = "Get the current time, date, day, battery percentage, or weather information of your location instantly.",
        descHi = "अपने स्थान का समय, तारीख, दिन, बैटरी प्रतिशत या मौसम की जानकारी तुरंत प्राप्त करें।",
        examples = listOf("\"time kya hua\"", "\"battery kitni hai\"", "\"mausam kaisa hai\""),
        accentColor = HeroBlue,
    ),
)

/**
 * Scrollable catalog of feature cards. Tapping a card opens a full-screen [DetailOverlay].
 * Recovered from FeaturesScreen().
 */
@Composable
fun FeaturesScreen() {
    var selectedFeature by remember { mutableStateOf<FeatureItem?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 90.dp),
        ) {
            item { FeaturesHeader() }
            items(FEATURES) { feature ->
                FeatureCard(feature = feature, onClick = { selectedFeature = feature })
                Spacer(Modifier.height(12.dp))
            }
        }

        AnimatedVisibility(
            visible = selectedFeature != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            selectedFeature?.let { feature ->
                DetailOverlay(feature = feature, onClose = { selectedFeature = null })
            }
        }
    }
}

/** Screen header block: "FEATURES" eyebrow / "App Features" title / subtitle. */
@Composable
private fun FeaturesHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 24.dp, bottom = 20.dp),
    ) {
        Text("FEATURES", color = W25, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text("App Features", color = W100, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
        Spacer(Modifier.height(6.dp))
        Text("Discover everything Ultron can do for you", color = W25, fontSize = 12.sp)
    }
}

/** A single tappable feature card: emoji, English title, accent badge, Hindi title. */
@Composable
fun FeatureCard(feature: FeatureItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(22)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(feature.emoji, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(feature.titleEn, color = W100, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (feature.badge.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(feature.badge, color = feature.accentColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(feature.titleHi, color = W35, fontSize = 12.sp)
            }
        }
    }
}

/** Full-screen detail sheet for a feature: header, EN/HI descriptions, and "try saying" examples. */
@Composable
private fun DetailOverlay(feature: FeatureItem, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
        ) {
            // Close (X) button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = W100,
                    modifier = Modifier.clickable(onClick = onClose),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Header: emoji + bilingual titles
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(feature.emoji, fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(feature.titleEn, color = W100, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(feature.titleHi, color = W35, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(20.dp))

            // English description
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🇬🇧", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("ENGLISH DESCRIPTION", color = feature.accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(feature.descEn, color = W100.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(20.dp))

            // Hindi description
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🇮🇳", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("HINDI DESCRIPTION", color = feature.accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(feature.descHi, color = W100.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(20.dp))

            // Example utterances
            Text("🗣️ TRY SAYING:", color = feature.accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            feature.examples.forEach { example ->
                Text(
                    text = example,
                    color = W100,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
