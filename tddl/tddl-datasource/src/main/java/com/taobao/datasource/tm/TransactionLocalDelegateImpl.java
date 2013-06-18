/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.taobao.datasource.tm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.jboss.util.NestedRuntimeException;

import EDU.oswego.cs.dl.util.concurrent.ConcurrentHashMap;
import EDU.oswego.cs.dl.util.concurrent.ReentrantLock;

/**
 * An implementation of the transaction local implementation
 * using Transaction synchronizations.
 *
 * There is one of these per transaction local
 *
 * @author <a href="mailto:adrian@jboss.org">Adrian Brock</a>
 * @version $Revision: 57208 $
 */
public class TransactionLocalDelegateImpl
   implements TransactionLocalDelegate
{
   // Attributes ----------------------------------------------------

   /** The transaction manager */
   protected TransactionManager manager;

   // Static --------------------------------------------------------

   /** The synchronizations for each transaction */
   protected static ConcurrentHashMap synchronizationsByTransaction = new ConcurrentHashMap();

   /**
    * Retrieve a synchronization for the transaction
    *
    * @param tx the transaction
    * @param create whether to create a synchronization if one doesn't exist
    */
   protected static TransactionLocalSynchronization getSynchronization(Transaction tx, boolean create)
   {
      synchronized (tx)
      {
         TransactionLocalSynchronization result = (TransactionLocalSynchronization) synchronizationsByTransaction.get(tx);
         if (result == null && create == true)
         {
            result = new TransactionLocalSynchronization(tx);
            try
            {
               tx.registerSynchronization(result);
            }
            catch (RollbackException e)
            {
               throw new IllegalStateException("Transaction already rolled back or marked for rollback");
            }
            catch (SystemException e)
            {
               throw new NestedRuntimeException(e);
            }
            synchronizationsByTransaction.put(tx, result);
         }
         return result;
      }
   }

   /**
    * Remove a synchronization
    *
    * @param tx the transaction to remove
    */
   protected static void removeSynchronization(Transaction tx)
   {
      synchronizationsByTransaction.remove(tx);
   }

   // Constructor ---------------------------------------------------

   /**
    * Construct a new delegate for the given transaction manager
    *
    * @param manager the transaction manager
    */
   public TransactionLocalDelegateImpl(TransactionManager manager)
   {
      this.manager = manager;
   }

   public void lock(TransactionLocal local, Transaction tx) throws InterruptedException
   {
      TransactionLocalSynchronization sync = getSynchronization(tx, true);
      sync.lock(local);
   }

   public void unlock(TransactionLocal local, Transaction tx)
   {
      TransactionLocalSynchronization sync = getSynchronization(tx, false);
      if (sync != null)
        sync.unlock(local);
      else
         throw new IllegalStateException("No synchronization found tx=" + tx + " local=" + local);
   }

   public Object getValue(TransactionLocal local, Transaction tx)
   {
      TransactionLocalSynchronization sync = getSynchronization(tx, false);
      if (sync == null)
         return null;
      return sync.getValue(local);
   }

   public void storeValue(TransactionLocal local, Transaction tx, Object value)
   {
      TransactionLocalSynchronization sync = getSynchronization(tx, true);
      sync.setValue(local, value);
   }

   public boolean containsValue(TransactionLocal local, Transaction tx)
   {
      TransactionLocalSynchronization sync = getSynchronization(tx, false);
      if (sync == null)
         return false;
      return sync.containsValue(local);
   }

   // InnerClasses ---------------------------------------------------

   protected static class TransactionLocalSynchronization
      implements Synchronization
   {
      protected Transaction tx;

      private Map valuesByLocal = Collections.synchronizedMap(new HashMap());

      protected ReentrantLock reentrantLock = new ReentrantLock();
      
      public TransactionLocalSynchronization(Transaction tx)
      {
         this.tx = tx;
      }

      public void beforeCompletion()
      {
      }

      public void afterCompletion(int status)
      {
         removeSynchronization(tx);
         valuesByLocal.clear(); // Help the GC
      }

      public void lock(Object local) throws InterruptedException
      {
         boolean locked = reentrantLock.attempt(60000);
         if (locked == false)
            throw new IllegalStateException("Failed to acquire lock within 60 seconds.");
      }
      
      public void unlock(Object local)
      {
         reentrantLock.release();
      }
      
      public Object getValue(Object local)
      {
         return valuesByLocal.get(local);
      }

      public void setValue(Object local, Object value)
      {
         valuesByLocal.put(local, value);
      }

      public boolean containsValue(Object local)
      {
         return valuesByLocal.containsKey(local);
      }
   }
}
