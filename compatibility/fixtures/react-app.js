// Local React 19.3 fixture; bundled by scripts/build-react-fixture.sh.
import React, {use, useState, Suspense} from 'react';
import {createRoot} from 'react-dom/client';
let release;
const pending = new Promise(resolve => { release = resolve; });
window.releaseSuspense = () => release('Ready');
function AsyncPanel() { return React.createElement('p', null, use(pending)); }
function Counter() {
  const [count, setCount] = useState(0);
  window.increment = () => setCount(previous => previous + 1);
  return React.createElement('button', {id: 'counter', onClick: window.increment}, 'Count ' + count);
}
function App() {
  return React.createElement('main', null, React.createElement(Counter),
    React.createElement(Suspense, {fallback: React.createElement('p', {id: 'waiting'}, 'Waiting')},
      React.createElement(AsyncPanel)));
}
createRoot(document.getElementById('root')).render(React.createElement(App));
