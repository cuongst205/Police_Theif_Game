const net = require('net');
const fs = require('fs');
const path = require('path');
const {spawn} = require('child_process');
const assert = require('assert/strict');
const root = path.resolve(__dirname, '..');
const cwd = fs.mkdtempSync(path.join(root, 'move-test-'));
const port = 15057;
const server = spawn('java', ['-cp', path.join(root, 'out') + path.delimiter + path.join(root, 'lib', '*'), 'com.networkgame.Launcher', 'server', String(port)], {cwd, env: {...process.env, GAME_MONGO_DATABASE: 'police_robber_socket_test_' + Date.now()}});
let peers = [];
function connect() {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1');
    socket.once('error', reject);
    socket.once('connect', () => {
      let buffer = '', queue = [], waiter;
      socket.on('data', bytes => {
        buffer += bytes.toString();
        let end;
        while ((end = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, end).trim(); buffer = buffer.slice(end + 1);
          queue.push(line);
          if (waiter) { const fn = waiter; waiter = null; fn(); }
        }
      });
      const client = {send: text => socket.write(text + '\n'), close: () => socket.destroy(),
        async until(prefix) {
          const expiry = Date.now() + 16000;
          while (true) {
            while (queue.length) {
              const line = queue.shift();
              if (line.startsWith('ERROR;')) throw new Error(line);
              if (line.startsWith(prefix)) return line.split(';');
            }
            await new Promise((ok, fail) => {
              const timer = setTimeout(() => {waiter = null; fail(new Error('Timed out: ' + prefix));}, Math.max(1, expiry - Date.now()));
              waiter = () => {clearTimeout(timer); ok();};
            });
          }
        }};
      peers.push(client); resolve(client);
    });
  });
}
(async () => {
  await new Promise((ok, fail) => {server.stdout.on('data', bytes => {if (bytes.toString().includes('listening on port')) ok();}); server.once('error', fail); setTimeout(() => fail(new Error('Server startup timeout')), 10000).unref();});
  const a = await connect(), b = await connect();
  for (const [client, name] of [[a, 'movealpha'], [b, 'movebeta']]) {
    await client.until('CONNECTED;'); client.send(`REGISTER;${name};password`); await client.until('REGISTER_OK;');
    client.send(`LOGIN;${name};password`); await client.until('LOGIN_OK;');
  }
  a.send('INVITE;movebeta'); await b.until('INVITE_FROM;'); b.send('ACCEPT;movealpha');
  await a.until('GAME_START;'); await b.until('GAME_START;');
  for (let n = 1; n <= 3; n++) {
    const [sa, sb] = await Promise.all([a.until('ROUND_START;'), b.until('ROUND_START;')]);
    assert.equal(sa[1], String(n)); assert.notEqual(sa[2], sb[2]);
    const allowedA = ['LEFT','MIDDLE','RIGHT'].filter(x => x !== sa[6]);
    const allowedB = ['LEFT','MIDDLE','RIGHT'].filter(x => x !== sb[6]);
    const da = allowedA[0];
    const db = n === 1 ? allowedB.find(x => x === da) || allowedB[0] : allowedB.find(x => x !== da);
    a.send(`MOVE;${da};${n}`); await a.until('MOVE_ACCEPTED;');
    if (n !== 3) b.send(`MOVE;${db};${n}`);
    const [ra, rb] = await Promise.all([a.until('ROUND_RESULT;'), b.until('ROUND_RESULT;')]);
    assert.equal(ra[5], da); assert.equal(rb[6], da);
    if (n !== 3) {
      assert.equal(ra[6], db); assert.equal(rb[5], db); assert.equal(ra[9], 'NORMAL');
      const policeWins = da === db;
      assert.equal(ra[2], (sa[2] === 'POLICE') === policeWins ? 'WIN' : 'LOSE');
    } else {assert.equal(ra[2], 'WIN'); assert.equal(ra[6], 'TIMEOUT'); assert.equal(rb[5], 'TIMEOUT');}
    console.log(`PASS round ${n}: ${da} / ${n === 3 ? 'TIMEOUT' : db}, result ${ra[2]}, cause ${ra[9]}`);
  }
  a.send('EXIT_GAME'); await a.until('MATCH_RESULT;');
})().catch(e => {console.error(e); process.exitCode = 1;}).finally(() => {peers.forEach(p => p.close()); server.kill(); console.log('Test data isolated in ' + cwd);});
