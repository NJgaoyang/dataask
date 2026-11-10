package com.dataask.mini;

/** Stable, human-readable saved visualization names derived from the question. */
final class ChartTitleFormatter {
    private ChartTitleFormatter() {}
    static String fromQuestion(String question) {
        if (question == null || question.isBlank()) return "我的图表";
        String name = question.strip()
            .replaceAll("^(?:(?:请帮我|帮我|麻烦帮我|麻烦|请问|请|我想知道|我想了解|想知道|查询一下|查询|查看一下|查看|看一下|看看|给我看下|帮忙统计|统计一下|展示一下|展示|分析一下|分析)+)", "")
            .replaceAll("(?:是多少|有多少|怎么样|如何|是什么|是啥|的数据|一下)[?？。！!]*$", "")
            .replaceAll("[?？。！!]+$", "")
            .replaceAll("\\s+","")
            .trim();
        if (name.isBlank()) name=question.strip();
        name=name.replace("昨天","昨日").replace("前一天","昨日");
        if (name.length()>30) name=name.substring(0,29)+"…";
        return name;
    }
}
