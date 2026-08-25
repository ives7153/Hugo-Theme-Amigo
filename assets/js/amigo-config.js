window.amigoConfig = {
    artalkServer: {{ .Site.Params.artalkServer | jsonify }},
    artalkSite: {{ .Site.Params.artalkSite | jsonify }},
    commentMode: {{ .Site.Params.commentMode | jsonify }},
    twikooEnvId: {{ .Site.Params.twikooEnvId | jsonify }},
    twikooLang: {{ (.Site.Params.twikooLang | default "zh-CN") | jsonify }},
    enableDanmaku: {{ if eq .Site.Params.enableDanmaku false }}false{{ else }}true{{ end }},
    headerMediaList: {{ with .Site.Params.headerMediaList }}{{ . | jsonify }}{{ else }}[]{{ end }},
    headerMedia: {{ (.Site.Params.headerMedia | default .Site.Params.cover) | jsonify }}
};