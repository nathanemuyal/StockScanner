# Stock Scanner — סריקת מיקומי מוצרים במחסן

אפליקציית Android מקומית וחד־פעמית: טוענים קובץ Excel של מוצרים, סורקים QR
של מדף ואז את הברקודים של המוצרים שעליו, ומעדכנים את עמודת `מיקום` (ואם
צריך גם `תאור`/`ברקוד`) — ובסוף מייצאים Excel מעודכן. הכול רץ על המכשיר,
בלי אינטרנט, בלי שרת.

## טכנולוגיה
- Kotlin, Android Views (ללא Compose)
- CameraX + Google ML Kit Barcode Scanning (QR, EAN-13, EAN-8, UPC-A/E, Code 128, Code 39)
- Room (SQLite) לאחסון מקומי — כדי שסגירת האפליקציה באמצע עבודה לא תאבד נתונים
- קורא/כותב XLSX פנימי, ללא Apache POI (ל־POI יש בעיות תאימות ידועות עם
  Android בגלל תלות ב־AWT; המימוש כאן עובד ישירות מול `java.util.zip`
  ו־`XmlPullParser` המובנה של אנדרואיד)

## מבנה הפרויקט
```
app/src/main/java/com/warehouse/stockscanner/
  MainActivity.kt              מסך ראשי ותזמור הזרימה
  ScannerActivity.kt           מסך סריקה (CameraX + ML Kit)
  ProductConfirmActivity.kt    מסך אישור/עריכה לפני שמירה
  SearchActivity.kt            חיפוש סלחני לפי תיאור
  SearchResultAdapter.kt
  StockScannerApp.kt           Application + חיבור למסד הנתונים
  data/                        Room (Entity/Dao/Database), Repository, SharedPreferences
  excel/                       ExcelReader / ExcelWriter (XLSX מותאם אישית)
  util/SearchUtils.kt          חיפוש סלחני (fuzzy) לפי תיאור
```

## מצב אימות (מעודכן)
הפרויקט **הודר בפועל** (לא רק נכתב) מול Android SDK אמיתי (`compileSdk 34`,
Gradle 8.4), וגם **21 בדיקות אוטומטיות** (JUnit + Robolectric) עוברות בהצלחה
— כולל קריאה של קבצי `.xlsx` אמיתיים שנוצרו באמצעות `openpyxl` (ספרייה
חיצונית, לא הקוד של האפליקציה עצמה), עם עמודות בסדר שונה, עמודה נוספת לא
רלוונטית, מקט עם אפסים מובילים, ותאים ריקים. ראו
[scripts/make_fixtures.py](scripts/make_fixtures.py) ליצירת קובצי הבדיקה,
ואת התיקייה `app/src/test/java` לבדיקות עצמן. הרצת הבדיקות:
```bash
./gradlew testDebugUnitTest
```
מה שעדיין **לא** נבדק בפועל: התנהגות אמיתית על מכשיר (מצלמה, RTL, מקלדת),
כי לסביבה שבה נכתב הקוד אין אמולטור/מכשיר מחובר.

## הוראות בנייה
ראו [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md).

## מגבלות מכוונות (בכוונה לא יושמו, לפי הדרישה לאפליקציה פשוטה וחד־פעמית)
- נקרא רק הגיליון הראשון (Sheet1) של קובץ ה־Excel.
- אין Login, משתמשים, הרשאות, שרת, ענן או מסדי נתונים חיצוניים.
- מקט (`מקט`) תמיד מטופל כטקסט — גם בקריאה וגם בכתיבה — כדי לא לאבד אפסים
  מובילים או תווים לא־מספריים.
