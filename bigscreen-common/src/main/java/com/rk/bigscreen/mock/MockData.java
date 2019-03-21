package com.rk.bigscreen.mock;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * @program: bigscreen
 * @class_name MockData
 * @author: rk
 * @create: 2019-03-18 16:59
 * @Description:
 *         本类主要用来模拟用户点击，进行数据生成
 *         bigscreen-common/data/taobao-info.log
 *         为了方便测试：建议通过两个参数进行控制--从第几条读取，读取几条
 *
 **/
public class MockData {
    public static void main(String[] args) throws IOException {
//        log4jTest();
        if (args == null || args.length < 1){
            System.err.println("Parameter Errors! Usage: <input> <start> <size>");
            System.exit(-1);
        }
        String input = args[0];
        int start = Integer.valueOf(args[1]);
        int size = Integer.valueOf(args[2]);
        Logger logger = Logger.getLogger("access");
        BufferedReader br = new BufferedReader(new FileReader(input));
        String msg = null;
        int count = 0;
        while ((msg = br.readLine()) != null){
            if(count >= start + size){
                break;
            }else if (count >= start){
//                System.out.println(msg);
//                try {
//                    Thread.sleep(1000);
//                    logger.info(msg);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
                logger.info(msg);
            }
            count++;
        }

        br.close();

    }

    private static void log4jTest() {
//        Logger logger = Logger.getLogger(MockData.class);
        Logger logger = Logger.getLogger("access");
        logger.debug("---debug---");
        logger.info("---info---");
        logger.warn("---warn---");
        logger.error("---error---");
    }
}
