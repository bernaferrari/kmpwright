package com.bernaferrari.kmpwright.example

/**
 * Self-contained pages the demos drive. They load with `page.setContent`, so
 * every demo works offline and gives the same accessibility tree each run.
 */
internal object DemoSite {

    private val baseStyle = """
        :root { color-scheme: light dark; --accent:#5b4fc4; --accent-2:#00696b; --bg:#fcf8ff; --card:#ffffff;
                --text:#1c1b22; --muted:#605d68; --line:#e5e0ef; --ok:#0b7a43; }
        @media (prefers-color-scheme: dark) {
          :root { --accent:#c8bfff; --accent-2:#80d4d5; --bg:#14131a; --card:#211f26; --text:#e5e1eb;
                  --muted:#c9c4d0; --line:#36343c; --ok:#7ddba2; }
        }
        * { box-sizing: border-box; }
        body { margin:0; font: 16px/1.5 system-ui, -apple-system, Roboto, sans-serif; background:var(--bg); color:var(--text); }
        main { max-width: 640px; margin: 0 auto; padding: 20px 18px 48px; }
        h1 { font-size: 30px; line-height: 1.15; margin: 8px 0 6px; letter-spacing: -0.02em; }
        h2 { font-size: 18px; margin: 0 0 12px; }
        p.lead { color: var(--muted); margin: 0 0 20px; }
        .card { background: var(--card); border: 1px solid var(--line); border-radius: 24px; padding: 18px; margin: 14px 0; }
        label { display:block; font-weight: 600; font-size: 14px; margin: 12px 0 6px; }
        input[type=text], input[type=email], input[type=search], select {
          width:100%; font: inherit; padding: 12px 14px; border-radius: 14px; border: 1.5px solid var(--line);
          background: transparent; color: inherit; }
        input:focus, select:focus { outline: 3px solid color-mix(in srgb, var(--accent) 35%, transparent); border-color: var(--accent); }
        button { font: inherit; font-weight: 600; border: 0; border-radius: 999px; padding: 12px 20px; cursor: pointer;
                 background: var(--accent); color: var(--bg); transition: transform .12s ease, border-radius .2s ease; }
        button:active { transform: scale(.96); border-radius: 14px; }
        button.secondary { background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent); }
        .row { display:flex; gap: 10px; align-items:center; flex-wrap: wrap; }
        .check { display:flex; gap:10px; align-items:center; font-weight: 500; margin-top: 14px; }
        .check input { width: 20px; height: 20px; accent-color: var(--accent); }
        .pill { display:inline-block; padding: 4px 10px; border-radius: 999px; font-size: 13px; font-weight: 600;
                background: color-mix(in srgb, var(--accent-2) 16%, transparent); color: var(--accent-2); }
        #signup-status { margin-top: 14px; font-weight: 600; color: var(--ok); min-height: 1.5em; }
        ul.results { list-style: none; padding: 0; margin: 12px 0 0; }
        ul.results li { padding: 12px 14px; border-radius: 14px; border: 1px solid var(--line); margin-bottom: 8px; }
        .hero { border-radius: 28px; padding: 26px 22px; color: white; margin-bottom: 18px;
                background: linear-gradient(135deg, #5b4fc4 0%, #8a4fa9 55%, #00696b 100%); }
        .hero p { opacity: .9; margin: 6px 0 0; }
    """.trimIndent()

    private fun page(title: String, body: String, script: String = "") = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>$title</title>
          <style>$baseStyle</style>
        </head>
        <body><main>$body</main><script>$script</script></body>
        </html>
    """.trimIndent()

    /** A small app with search, a sign-up form and a counter: everything an agent needs to practise on. */
    val playground: String = page(
        title = "Kmpwright Playground",
        body = """
            <div class="hero">
              <span class="pill" style="background:rgba(255,255,255,.18);color:white">Playground</span>
              <h1>Hello, agent 👋</h1>
              <p>Snapshot this page, then act on the refs.</p>
            </div>

            <section class="card" aria-labelledby="search-title">
              <h2 id="search-title">Search the docs</h2>
              <form id="search" class="row" role="search">
                <input id="query" type="search" aria-label="Search" placeholder="Try “locator”" style="flex:1">
                <button type="submit">Search</button>
              </form>
              <ul class="results" id="results" aria-label="Results"></ul>
            </section>

            <section class="card" aria-labelledby="signup-title">
              <h2 id="signup-title">Create an account</h2>
              <form id="signup">
                <label for="full-name">Full name</label>
                <input id="full-name" type="text" autocomplete="name" placeholder="Ada Lovelace">
                <label for="email">Email</label>
                <input id="email" type="email" autocomplete="email" placeholder="ada@example.com">
                <label for="plan">Plan</label>
                <select id="plan">
                  <option value="hobby">Hobby — free</option>
                  <option value="pro">Pro — ${'$'}12/mo</option>
                  <option value="team">Team — ${'$'}49/mo</option>
                </select>
                <label class="check"><input id="terms" type="checkbox"> I agree to the terms</label>
                <div class="row" style="margin-top:16px">
                  <button type="submit">Create account</button>
                  <button type="reset" class="secondary">Reset</button>
                </div>
                <p id="signup-status" role="status" aria-live="polite"></p>
              </form>
            </section>

            <section class="card" aria-labelledby="counter-title">
              <h2 id="counter-title">Counter</h2>
              <div class="row">
                <button id="decrement" class="secondary" aria-label="Decrement">−</button>
                <output id="count" aria-label="Count" style="font-size:28px;font-weight:700;min-width:48px;text-align:center">0</output>
                <button id="increment" aria-label="Increment">+</button>
              </div>
            </section>
        """.trimIndent(),
        script = """
            const ${'$'} = id => document.getElementById(id);
            const docs = ['Locator API', 'Page.goto', 'Accessibility snapshot', 'Network routes', 'Screenshots', 'Keyboard input'];
            ${'$'}('search').addEventListener('submit', e => {
              e.preventDefault();
              const q = ${'$'}('query').value.trim().toLowerCase(), list = ${'$'}('results');
              list.innerHTML = '';
              docs.filter(d => d.toLowerCase().includes(q)).forEach(d => {
                const li = document.createElement('li'); li.textContent = d; list.appendChild(li);
              });
              if (!list.children.length) list.innerHTML = '<li>No results</li>';
            });
            ${'$'}('signup').addEventListener('submit', e => {
              e.preventDefault();
              const out = ${'$'}('signup-status'), plan = ${'$'}('plan');
              if (!${'$'}('terms').checked) { out.style.color = '#ba1a1a'; out.textContent = 'Please accept the terms.'; return; }
              out.style.color = '';
              out.textContent = 'Welcome, ' + (${'$'}('full-name').value || 'friend') + '! You picked ' +
                plan.options[plan.selectedIndex].text.split(' —')[0] + '.';
            });
            ${'$'}('signup').addEventListener('reset', () => { ${'$'}('signup-status').textContent = ''; });
            let n = 0;
            ${'$'}('increment').onclick = () => ${'$'}('count').textContent = ++n;
            ${'$'}('decrement').onclick = () => ${'$'}('count').textContent = --n;
            console.log('Playground ready');
        """.trimIndent(),
    )

    /** A long article for scrolling and full-page screenshots. */
    val article: String = page(
        title = "Field notes",
        body = buildString {
            append("""<div class="hero"><h1>Field notes</h1><p>A long page for scrolling and full-page capture.</p></div>""")
            val colors = listOf("#5b4fc4", "#8a4fa9", "#00696b", "#b3261e", "#7d5260", "#386a20")
            colors.forEachIndexed { index, color ->
                append(
                    """
                    <section class="card">
                      <h2><span style="display:inline-block;width:14px;height:14px;border-radius:5px;background:$color;margin-right:8px"></span>Chapter ${index + 1}</h2>
                      <p>Browsers are documents, scripts and pixels. Automation is the art of asking all three the same question and getting one answer.</p>
                      <div style="height:180px;border-radius:20px;background:linear-gradient(135deg,$color,color-mix(in srgb,$color 40%,white))"></div>
                    </section>
                    """.trimIndent()
                )
            }
        },
    )

    /** Fetches a quote from an API the recipe mocks with `page.route`. */
    val quote: String = page(
        title = "Quote of the day",
        body = """
            <div class="hero"><h1>Quote of the day</h1><p>Loaded from a network API.</p></div>
            <section class="card"><blockquote id="quote" style="font-size:22px;margin:0">Loading…</blockquote>
            <p id="author" class="lead" style="margin-top:12px"></p></section>
        """.trimIndent(),
        script = """
            const quote = document.getElementById('quote'), author = document.getElementById('author');
            fetch('$QUOTE_API').then(r => r.json()).then(q => {
              quote.textContent = '“' + q.text + '”'; author.textContent = '— ' + q.author;
            }).catch(e => { quote.textContent = 'Request failed: ' + e.message; });
        """.trimIndent(),
    )

    const val QUOTE_API = "https://api.kmpwright.dev/quote"
}
