package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outcome of [Page.login]; `submitted` does not prove the login succeeded. */
public data class AuthLoginResult(val url: String, val submitted: Boolean)

/**
 * Fill configured controls or discover usable controls within the password form. Credential entry
 * runs in one document task; changed targets fail before the remaining credential is written.
 * Input/change events are synthetic. A submitted result is not proof of successful authentication.
 */
internal suspend fun Page.loginWithProfile(profile: AuthProfile, navigate: Boolean, submit: Boolean): AuthLoginResult {
    val expectedOrigin = canonicalHttpOrigin(normalizeReaderUrl(profile.url))
        ?: throw KmpwrightException("Auth profile requires an HTTP(S) URL")
    if (navigate) goto(profile.url)
    if (canonicalHttpOrigin(url.value) != expectedOrigin) throw KmpwrightException("Auth target origin does not match the saved profile")
    val result = evaluate("""
        (function(){
          var initialDocument=document, initialUrl=location.href;
          if (location.origin !== ${jsStringLiteral(expectedOrigin)}) throw new Error('Auth target origin changed');
          function usable(el) {
            if (!el || !el.isConnected || el.disabled || el.readOnly || el.getAttribute('aria-disabled')==='true') return false;
            var style=getComputedStyle(el), rect=el.getBoundingClientRect();
            return style.display!=='none' && style.visibility!=='hidden' && Number(style.opacity)!==0 && rect.width>0 && rect.height>0;
          }
          function selected(selector, fallback) {
            if (!selector) return fallback();
            var matches=Array.from(document.querySelectorAll(selector)).filter(usable);
            if (matches.length!==1) throw new Error('Auth selector must match exactly one usable control: '+selector);
            return matches[0];
          }
          var password=selected(${profile.passwordSelector?.let(::jsStringLiteral) ?: "null"},function(){
            var fields=Array.from(document.querySelectorAll('input[type=password]')).filter(usable);
            if (fields.length!==1) throw new Error('Could not select one usable password control');
            return fields[0];
          });
          if (!password || password.tagName!=='INPUT' || password.type!=='password') throw new Error('Auth password target must be a password input');
          var form=password.form, root=form || document;
          var username=selected(${profile.usernameSelector?.let(::jsStringLiteral) ?: "null"},function(){
            var fields=Array.from(root.querySelectorAll('input')).filter(function(el){return usable(el) && /^(text|email|tel)$/.test(el.type);});
            var ranked=fields.filter(function(el){return el.autocomplete==='username' || el.autocomplete==='email' || /user|email|login/i.test(el.name+' '+el.id);});
            if (!ranked.length) ranked=fields.filter(function(el){return el.type==='email';});
            if (!ranked.length) ranked=fields;
            if (ranked.length!==1) throw new Error('Could not select one usable username control');
            return ranked[0];
          });
          if (!username || username.tagName!=='INPUT' || !/^(text|email|tel)$/.test(username.type)) throw new Error('Auth username target must be an editable input');
          var submit=${submit} ? selected(${profile.submitSelector?.let(::jsStringLiteral) ?: "null"},function(){
            var buttons=Array.from(root.querySelectorAll('button[type=submit],input[type=submit],button:not([type])')).filter(usable);
            if (buttons.length!==1) throw new Error('Could not select one usable submit control');
            return buttons[0];
          }) : null;
          var formAction=form && form.action, formMethod=form && form.method;
          var submitAction=submit && submit.formAction;
          function identity(el) {return JSON.stringify([el.id,el.name,el.type,el.autocomplete,el.form && el.form.id]);}
          var usernameIdentity=identity(username), passwordIdentity=identity(password);
          function sameSelector(selector,el) {if (!selector) return true;var matches=Array.from(document.querySelectorAll(selector)).filter(usable);return matches.length===1 && matches[0]===el;}
          function unchanged() {
            if (document!==initialDocument || location.href!==initialUrl || !usable(username) || !usable(password) ||
                username.form!==form || password.form!==form || (submit && !usable(submit))) throw new Error('Auth credential targets changed');
            if (password.type!=='password' || !/^(text|email|tel)$/.test(username.type) ||
                identity(username)!==usernameIdentity || identity(password)!==passwordIdentity ||
                !sameSelector(${profile.usernameSelector?.let(::jsStringLiteral) ?: "null"},username) ||
                !sameSelector(${profile.passwordSelector?.let(::jsStringLiteral) ?: "null"},password)) throw new Error('Auth credential target type or identity changed');
            if (form && (form.action!==formAction || form.method!==formMethod || new URL(form.action,initialUrl).origin!==location.origin)) throw new Error('Auth form destination changed or has a foreign origin');
            if (submit && (submit.form!==form || submit.formAction!==submitAction ||
                (submit.hasAttribute('formaction') && new URL(submit.formAction,initialUrl).origin!==location.origin))) throw new Error('Auth submit destination changed or has a foreign origin');
          }
          ${NATIVE_VALUE_SETTER_JS}
          function fill(el,value) {
            unchanged(); el.focus(); unchanged();
            kmpwrightSetNativeValue(el,value);
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }
          unchanged();
          fill(username,${jsStringLiteral(profile.username)});
          fill(password,${jsStringLiteral(profile.password)});
          unchanged();
          if (submit) submit.click();
          return JSON.stringify({url:initialUrl,submitted:!!submit});
        })()
    """.trimIndent()).value
    val parsed = Json.parseToJsonElement(result).jsonObject
    return AuthLoginResult(parsed.getValue("url").jsonPrimitive.content,
        parsed["submitted"]?.jsonPrimitive?.booleanOrNull == true)
}

/**
 * Declares `kmpwrightSetNativeValue(el, value)`, which writes through the *prototype* `value` setter of
 * the element's class. Frameworks such as React install their own instance-level value setter to track
 * changes; writing through the prototype setter (then dispatching `input`) is how a real edit looks to
 * them. Shared by the page runtime ([PageHelpers]) and credential login.
 */
internal const val NATIVE_VALUE_SETTER_JS: String = """
function kmpwrightSetNativeValue(el, value) {
  var view = (el.ownerDocument && el.ownerDocument.defaultView) || window;
  var proto = el.tagName === 'TEXTAREA' ? view.HTMLTextAreaElement.prototype
    : el.tagName === 'SELECT' ? view.HTMLSelectElement.prototype : view.HTMLInputElement.prototype;
  var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
  if (descriptor && descriptor.set) descriptor.set.call(el, value); else el.value = value;
}
"""
