import { Telegraf } from 'telegraf';
import ccxt from 'ccxt';

/**
 * GHOST_BRIDGE: Unified Trading & Approval Bridge.
 * Controls Kraken Trading and MetaMask GHOST_SIGNER via Telegram.
 */

const bot = new Telegraf(process.env.TELEGRAM_BOT_TOKEN || '');
const kraken = new ccxt.kraken({
    apiKey: process.env.KRAKEN_API_KEY,
    secret: process.env.KRAKEN_PRIVATE_KEY,
});

// 1. Trading Command: /buy <symbol> <amount>
bot.command('buy', async (ctx) => {
    const [, symbol, amount] = ctx.message.text.split(' ');
    try {
        const order = await kraken.createMarketBuyOrder(symbol, amount);
        ctx.reply(`✅ Market Buy Filled: ${order.id}\nSymbol: ${symbol}\nAmount: ${amount}`);
    } catch (e) {
        ctx.reply(`❌ Order Failed: ${e.message}`);
    }
});

// 2. Status Command: /status
bot.command('status', async (ctx) => {
    try {
        const balance = await kraken.fetchBalance();
        const jasmy = balance.total['JASMY'] || 0;
        ctx.reply(`📊 Current Portfolio:\nJASMY: ${jasmy}\nUSD: ${balance.total['USD']}\n\nGHOST_SIGNER: Active`);
    } catch (e) {
        ctx.reply(`❌ Status Check Failed: ${e.message}`);
    }
});

// 3. Approval Hook: Handle MetaMask Signing Requests
bot.on('callback_query', async (ctx) => {
    const action = ctx.callbackQuery.data;
    if (action.startsWith('approve_')) {
        const txHash = action.split('_')[1];
        // Logic to trigger GHOST_SIGNER via RPC
        ctx.reply(`🛡️ Transaction Approved via Telegram: ${txHash}`);
    }
});

bot.launch();
console.log('GHOST_BRIDGE: Telegram Trading Core Running');
