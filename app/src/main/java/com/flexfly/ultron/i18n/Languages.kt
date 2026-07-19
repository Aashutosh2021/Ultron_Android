package com.flexfly.ultron.i18n

import com.flexfly.ultron.model.LanguageInfo

/**
 * The assistant's supported languages/dialects (53 entries), each with an English name,
 * a native-script name, and a region label. Recovered verbatim from `Languages.kt`.
 *
 * The Compose `LanguagePickerDialog` that consumes this list is reconstructed separately
 * in the UI layer; this file holds only the data (a pure logic dependency of
 * [com.flexfly.ultron.ai.GeminiLiveSession] and the settings/onboarding screens).
 */
val INDIAN_LANGUAGES: List<LanguageInfo> = listOf(
    LanguageInfo("Hinglish", "Hinglish", "Common / Digital India"),
    LanguageInfo("English", "English", "India / Global"),
    LanguageInfo("Hindi", "हिन्दी", "North & Central India"),
    LanguageInfo("Urdu", "اردو", "Common"),
    LanguageInfo("Sanskrit", "संस्कृतम्", "Heritage Language"),
    LanguageInfo("Nimadi", "निमाड़ी", "Madhya Pradesh (Nimar)"),
    LanguageInfo("Malvi", "मालवी", "Madhya Pradesh (Malwa)"),
    LanguageInfo("Bundeli", "बुंदेली", "Madhya Pradesh / Uttar Pradesh"),
    LanguageInfo("Bagheli", "बघेली", "Madhya Pradesh (Baghelkhand)"),
    LanguageInfo("Bhili", "भीली", "MP / Gujarat / Rajasthan"),
    LanguageInfo("Gondi", "गोंडी", "MP / Chhattisgarh / Maharashtra"),
    LanguageInfo("Chhattisgarhi", "छत्तीसगढ़ी", "Chhattisgarh"),
    LanguageInfo("Halbi", "हलबी", "Chhattisgarh (Bastar)"),
    LanguageInfo("Sargujia", "सरगुजिहा", "Chhattisgarh (Surguja)"),
    LanguageInfo("Marwari", "मारवाड़ी", "Rajasthan (Marwar)"),
    LanguageInfo("Mewari", "मेवाड़ी", "Rajasthan (Mewar)"),
    LanguageInfo("Harauti", "हाड़ौती", "Rajasthan (Hadoti)"),
    LanguageInfo("Shekhawati", "शेखावाटी", "Rajasthan (Shekhawati)"),
    LanguageInfo("Mewati", "मेवाती", "Rajasthan (Mewat)"),
    LanguageInfo("Dhundhari", "ढूंढाड़ी", "Rajasthan (Jaipur)"),
    LanguageInfo("Bhojpuri", "भोजपुरी", "Bihar / Jharkhand / UP"),
    LanguageInfo("Maithili", "मैथिली", "Bihar (Mithila)"),
    LanguageInfo("Magahi", "मगही", "Bihar (Magadh)"),
    LanguageInfo("Angika", "अंगिका", "Bihar / Jharkhand"),
    LanguageInfo("Santali", "संताली", "Jharkhand / Odisha / West Bengal"),
    LanguageInfo("Kurukh", "कुरुख", "Jharkhand / Chhattisgarh"),
    LanguageInfo("Haryanvi", "हरियाणवी", "Haryana"),
    LanguageInfo("Punjabi", "ਪੰਜਾਬੀ", "Punjab"),
    LanguageInfo("Himachali", "हिमाचली", "Himachal Pradesh"),
    LanguageInfo("Garhwali", "गढ़वाली", "Uttarakhand (Garhwal)"),
    LanguageInfo("Kumaoni", "कुमाऊनी", "Uttarakhand (Kumaon)"),
    LanguageInfo("Dogri", "डोगरी", "Jammu & Kashmir (Jammu)"),
    LanguageInfo("Kashmiri", "कॉशुर / كٲشुر", "Jammu & Kashmir (Kashmir)"),
    LanguageInfo("Gujarati", "ગુજરાતી", "Gujarat"),
    LanguageInfo("Marathi", "मराठी", "Maharashtra"),
    LanguageInfo("Konkani", "कोंकणी", "Goa / Konkan"),
    LanguageInfo("Kokani", "कोकणी", "Maharashtra (Konkan)"),
    LanguageInfo("Tamil", "தமிழ்", "Tamil Nadu"),
    LanguageInfo("Telugu", "తెలుగు", "Andhra Pradesh / Telangana"),
    LanguageInfo("Kannada", "ಕನ್ನಡ", "Karnataka"),
    LanguageInfo("Malayalam", "മലയാളം", "Kerala"),
    LanguageInfo("Tulu", "ತುಳು", "Karnataka (Tulu Nadu)"),
    LanguageInfo("Kodava", "ಕೊಡವ", "Karnataka (Kodagu)"),
    LanguageInfo("Bengali", "বাংলা", "West Bengal / Tripura"),
    LanguageInfo("Odia", "ଓଡ଼ିଆ", "Odisha"),
    LanguageInfo("Assamese", "অসমীয়া", "Assam"),
    LanguageInfo("Bodo", "बर'", "Assam"),
    LanguageInfo("Manipuri", "মৈতৈলোন্", "Manipur"),
    LanguageInfo("Mizo", "Mizo tawng", "Mizoram"),
    LanguageInfo("Khasi", "Khasi", "Meghalaya"),
    LanguageInfo("Garo", "Garo", "Meghalaya"),
    LanguageInfo("Kokborok", "Kókborok", "Tripura"),
    LanguageInfo("Nagamese", "Nagamese", "Nagaland"),
)
