layout(location = 0) in vec3 pos;
layout(location = 1) in vec3 n;
layout(location = 2) in vec4 t;
layout(location = 3) in vec2 mid;
layout(location = 4) in vec2 uv;
layout(location = 5) flat in vec4 entity;
// Green: every check holds. Red bit: normal, blue bit: tangent; red+blue: midTexCoord.
// Stone (id 1) and water (8, 9) are tinted dimmer so the ids show.
void main() {
    vec4 c = texture(gtexture, uv);
    vkm_alphaTest(c.a);
    vec3 dpx = dFdx(pos), dpy = dFdy(pos);
    vec3 geo = cross(dpx, dpy);
    bool flat_ = length(geo) > 1e-7;
    geo = normalize(geo);
    // Clockwise front faces in pack space flip the screen-space cross product: compare up to sign.
    bool badNormal = flat_ && abs(dot(normalize(n), geo)) < 0.98;
    // The tangent lies in the plane, and along it v stays constant.
    vec2 dux = dFdx(uv), duy = dFdy(uv);
    float det = dux.x * duy.y - dux.y * duy.x;
    bool badTangent = false;
    if (abs(det) > 1e-14) {
        vec3 tg = (dpx * duy.y - dpy * dux.y) / det;
        badTangent = abs(dot(normalize(tg), normalize(t.xyz))) < 0.95 || abs(dot(normalize(t.xyz), normalize(n))) > 0.1;
    }
    bool badMid = max(abs(uv.x - mid.x), abs(uv.y - mid.y)) > 0.04;
    float shade = (entity.x == 1.0 || entity.x == 8.0 || entity.x == 9.0) ? 0.5 : 1.0;
    vec3 ok = vec3(0.0, 1.0, 0.0) * shade;
    if (badNormal) ok = vec3(1.0, 0.0, 0.0);
    if (badTangent) ok = vec3(0.0, 0.0, 1.0);
    if (badMid) ok = vec3(1.0, 0.0, 1.0);
    sceneColor = vec4(ok, 1.0);
}
