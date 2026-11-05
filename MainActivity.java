    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        // اسأل الصفحة: هل فيه شاشة داخلية تتقفل؟ لو أيوه تتقفل، لو لأ نفضل في التطبيق
        web.evaluateJavascript(
            "(function(){try{return window.sirajBack?String(window.sirajBack()):'none';}catch(e){return 'none';}})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    // 'handled' = الصفحة قفلت شاشة داخلية، مفيش حاجة تانية نعملها
                    if (v != null && v.contains("handled")) return;
                    // غير كده: لا نخرج من التطبيق (الرئيسية/القرآن/التسبيح/الصلاة/الأذكار)
                    // نكتفي بإننا ما نعملش حاجة
                }
            });
    }
